use std::path::Path;
use keepass::{Database, DatabaseKey};

fn has_unsupported_fields(db: &Database) -> bool {
    if !db.meta.custom_data.is_empty() { return true; }
    for g in db.iter_all_groups() {
        if !g.custom_data.is_empty() { return true; }
    }
    for e in db.iter_all_entries() {
        if !e.custom_data.is_empty() { return true; }
    }
    false
}

pub async fn merge_and_save(
    uploaded_bytes: &[u8],
    password: &str,
    master_path: &Path,
    force: bool,
) -> Result<String, String> {
    let mut cursor = std::io::Cursor::new(uploaded_bytes);
    let key = DatabaseKey::new().with_password(password);
    
    // 1. Decrypt uploaded DB to ensure validity
    let uploaded_db = Database::open(&mut cursor, key.clone())
        .map_err(|e| format!("Failed to decrypt uploaded db: {:?}", e))?;

    if !force && has_unsupported_fields(&uploaded_db) {
        return Err("UNSUPPORTED_FIELDS_DETECTED".to_string());
    }

    if master_path.exists() {
        let master_bytes = std::fs::read(master_path).map_err(|e| e.to_string())?;
        let mut master_cursor = std::io::Cursor::new(master_bytes);
        
        // Ensure master can also be decrypted with the same key
        let mut master_db = Database::open(&mut master_cursor, key.clone())
            .map_err(|e| format!("Failed to decrypt master db (password mismatch?): {:?}", e))?;
            
        if !force && has_unsupported_fields(&master_db) {
            return Err("UNSUPPORTED_FIELDS_DETECTED".to_string());
        }

        tracing::info!("Intelligent KDBX merge active: merging uploaded DB into master DB");
        
        let merge_log = master_db.merge(&uploaded_db).map_err(|e| format!("Database merge failed: {:?}", e))?;
        tracing::debug!("Merge complete. Log: {} events applied", merge_log.events.len());

        let mut out_file = std::fs::File::create(master_path).map_err(|e| e.to_string())?;
        master_db.save(&mut out_file, key).map_err(|e| format!("Failed to save merged db: {:?}", e))?;

        Ok("Database merged successfully".to_string())
    } else {
        tracing::info!("Master DB does not exist, creating new one from uploaded DB");
        std::fs::write(master_path, uploaded_bytes).map_err(|e| e.to_string())?;
        Ok("Master database created successfully".to_string())
    }
}
