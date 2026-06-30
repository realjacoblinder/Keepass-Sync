mod api;
mod kdbx;

use axum::{
    routing::{get, post},
    Router,
};
use tower_http::services::{ServeDir, ServeFile};
use std::path::Path;
use tracing::info;

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt::init();

    // Ensure data directory exists
    let data_dir = Path::new("data");
    if !data_dir.exists() {
        std::fs::create_dir_all(data_dir).expect("Failed to create data directory");
        info!("Created data directory");
    }

    // Build our application with a route
    let api_routes = Router::new()
        .route("/sync", post(api::sync_handler))
        .route("/download", post(api::download_handler))
        .route("/status", post(api::status_handler));

    let app = Router::new()
        .nest("/api", api_routes)
        // Fallback for static files (production)
        .fallback_service(
            ServeDir::new("dist")
                .not_found_service(ServeFile::new("dist/index.html")),
        );

    // Run it
    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
    info!("listening on {}", listener.local_addr().unwrap());
    axum::serve(listener, app).await.unwrap();
}
