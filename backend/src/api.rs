use axum::{
    extract::Multipart,
    http::{header, StatusCode},
    response::IntoResponse,
    Json,
};
use std::path::PathBuf;
use serde::Deserialize;
use crate::kdbx;

#[derive(Deserialize)]
pub struct DownloadReq {
    #[serde(rename = "masterName", default = "default_master")]
    master_name: String,
}

#[derive(Deserialize)]
pub struct StatusReq {
    #[serde(rename = "masterName", default = "default_master")]
    master_name: String,
}

fn sanitize_name(name: &str) -> String {
    name.chars().filter(|c| c.is_alphanumeric() || *c == '_' || *c == '-').collect()
}

pub async fn sync_handler(mut multipart: Multipart) -> impl IntoResponse {
    let mut password = None;
    let mut db_file = None;
    let mut master_name_raw = None;
    let mut force = false;

    while let Some(field) = multipart.next_field().await.unwrap_or(None) {
        let name = field.name().unwrap_or("").to_string();
        if name == "password" {
            password = Some(field.text().await.unwrap_or_default());
        } else if name == "masterName" {
            master_name_raw = Some(field.text().await.unwrap_or_default());
        } else if name == "dbFile" {
            db_file = Some(field.bytes().await.unwrap_or_default());
        } else if name == "force" {
            force = field.text().await.unwrap_or_default() == "true";
        }
    }

    let password = match password {
        Some(p) if !p.is_empty() => p,
        _ => return (StatusCode::BAD_REQUEST, Json(serde_json::json!({ "error": "Password required" }))).into_response(),
    };

    let db_file = match db_file {
        Some(f) if !f.is_empty() => f,
        _ => return (StatusCode::BAD_REQUEST, Json(serde_json::json!({ "error": "File required" }))).into_response(),
    };

    let master_name = sanitize_name(&master_name_raw.unwrap_or_else(default_master));
    if master_name.is_empty() {
        return (StatusCode::BAD_REQUEST, Json(serde_json::json!({ "error": "Invalid master name" }))).into_response();
    }

    let master_path = PathBuf::from(format!("data/{}.kdbx", master_name));

    match kdbx::merge_and_save(&db_file, &password, &master_path, force).await {
        Ok(msg) => (StatusCode::OK, Json(serde_json::json!({ "message": msg }))).into_response(),
        Err(e) if e == "UNSUPPORTED_FIELDS_DETECTED" => {
            (StatusCode::CONFLICT, Json(serde_json::json!({ "error": "Unsupported fields detected in uploaded database. Merge may drop data. Set force=true to continue." }))).into_response()
        }
        Err(e) => {
            tracing::error!("[Secure] Sync error occurred. Details omitted to protect sensitive data.");
            (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({ "error": "Internal server error during sync", "details": e }))).into_response()
        }
    }
}

pub async fn download_handler(Json(payload): Json<DownloadReq>) -> impl IntoResponse {
    let master_name = sanitize_name(&payload.master_name);
    if master_name.is_empty() {
        return (StatusCode::BAD_REQUEST, Json(serde_json::json!({ "error": "Invalid master name" }))).into_response();
    }

    let master_path = format!("data/{}.kdbx", master_name);
    if !std::path::Path::new(&master_path).exists() {
        return (StatusCode::NOT_FOUND, Json(serde_json::json!({ "error": "Master database not found" }))).into_response();
    }

    match std::fs::read(&master_path) {
        Ok(content) => {
            (
                StatusCode::OK,
                [(header::CONTENT_DISPOSITION, format!("attachment; filename=\"{}.kdbx\"", master_name))],
                content,
            ).into_response()
        }
        Err(_) => (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({ "error": "Failed to read file" }))).into_response()
    }
}

pub async fn status_handler(Json(payload): Json<StatusReq>) -> impl IntoResponse {
    let master_name = sanitize_name(&payload.master_name);
    if master_name.is_empty() {
        return (StatusCode::BAD_REQUEST, Json(serde_json::json!({ "error": "Invalid master name" }))).into_response();
    }

    let master_path = format!("data/{}.kdbx", master_name);
    if let Ok(metadata) = std::fs::metadata(&master_path) {
        if let Ok(modified) = metadata.modified() {
            let datetime: chrono::DateTime<chrono::Utc> = modified.into();
            return (StatusCode::OK, Json(serde_json::json!({ "lastUpdated": datetime.to_rfc3339() }))).into_response();
        }
    }
    
    (StatusCode::OK, Json(serde_json::json!({ "lastUpdated": serde_json::Value::Null }))).into_response()
}

fn default_master() -> String {
    "master".to_string()
}
