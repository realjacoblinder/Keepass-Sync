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
        merge_databases(&mut master_db, &uploaded_db)?;

        let mut out_file = std::fs::File::create(master_path).map_err(|e| e.to_string())?;
        master_db.save(&mut out_file, key).map_err(|e| format!("Failed to save merged db: {:?}", e))?;

        Ok("Database merged successfully".to_string())
    } else {
        tracing::info!("Master DB does not exist, creating new one from uploaded DB");
        std::fs::write(master_path, uploaded_bytes).map_err(|e| e.to_string())?;
        Ok("Master database created successfully".to_string())
    }
}

fn merge_databases(master: &mut Database, uploaded: &Database) -> Result<(), String> {
    // Merge deleted objects
    for (uuid, del_time) in &uploaded.deleted_objects {
        if let Some(master_del_time) = master.deleted_objects.get(uuid) {
            if let (Some(t1), Some(t2)) = (del_time, master_del_time) {
                if t1 > t2 {
                    master.deleted_objects.insert(*uuid, *del_time);
                }
            } else if del_time.is_some() {
                master.deleted_objects.insert(*uuid, *del_time);
            }
        } else {
            master.deleted_objects.insert(*uuid, *del_time);
        }
    }

    // Process all groups in breadth-first order so parents exist
    let mut group_queue = vec![uploaded.root().id()];
    
    while let Some(up_group_id) = group_queue.pop() {
        let up_group = uploaded.group(up_group_id).unwrap();
        for child in up_group.groups() {
            group_queue.push(child.id());
        }

        let u_id = up_group.id();
        
        // Root group handling
        if u_id == uploaded.root().id() {
            let mut m_root = master.root_mut();
            if up_group.times.last_modification > m_root.times.last_modification {
                m_root.name = up_group.name.clone();
                m_root.notes = up_group.notes.clone();
                m_root.tags = up_group.tags.clone();
                m_root.custom_data = up_group.custom_data.clone();
                m_root.times = up_group.times.clone();
                if let Some(icon) = up_group.icon() {
                    match icon {
                        keepass::db::Icon::BuiltIn(id) => m_root.set_icon_builtin(*id as usize),
                        keepass::db::Icon::Custom(_) => {} 
                    }
                }
            }
        } else if master.group(u_id).is_some() {
            // Update existing group
            let mut m_group = master.group_mut(u_id).unwrap();
            if up_group.times.last_modification > m_group.times.last_modification {
                m_group.name = up_group.name.clone();
                m_group.notes = up_group.notes.clone();
                m_group.tags = up_group.tags.clone();
                m_group.custom_data = up_group.custom_data.clone();
                m_group.times = up_group.times.clone();
                if let Some(icon) = up_group.icon() {
                    match icon {
                        keepass::db::Icon::BuiltIn(id) => m_group.set_icon_builtin(*id as usize),
                        keepass::db::Icon::Custom(_) => {} 
                    }
                }
            }
            if let Some(up_parent) = up_group.parent() {
                let current_m_parent_id = m_group.parent_mut().map(|p| p.id());
                if current_m_parent_id != Some(up_parent.id()) {
                    let _ = m_group.move_to(up_parent.id());
                }
            }
        } else if let Some(up_parent) = up_group.parent() {
            // Create new group
            let parent_id = up_parent.id();
            if master.group(parent_id).is_some() {
                let mut m_parent = master.group_mut(parent_id).unwrap();
                if let Ok(mut new_m_group) = m_parent.add_group_with_id(u_id) {
                    new_m_group.name = up_group.name.clone();
                    new_m_group.notes = up_group.notes.clone();
                    new_m_group.tags = up_group.tags.clone();
                    new_m_group.custom_data = up_group.custom_data.clone();
                    new_m_group.times = up_group.times.clone();
                    if let Some(icon) = up_group.icon() {
                        match icon {
                            keepass::db::Icon::BuiltIn(id) => new_m_group.set_icon_builtin(*id as usize),
                            keepass::db::Icon::Custom(_) => {} 
                        }
                    }
                }
            }
        }

        // Process entries for this group
        for up_entry in up_group.entries() {
            let e_id = up_entry.id();
            if master.entry(e_id).is_some() {
                // Update existing entry
                let mut m_entry = master.entry_mut(e_id).unwrap();
                if up_entry.times.last_modification > m_entry.times.last_modification {
                    // TODO: Implement History merging here. Push older entry state into the history list.
                    m_entry.fields = up_entry.fields.clone();
                    m_entry.tags = up_entry.tags.clone();
                    m_entry.custom_data = up_entry.custom_data.clone();
                    m_entry.times = up_entry.times.clone();
                    m_entry.autotype = up_entry.autotype.clone();
                    if let Some(icon) = up_entry.icon() {
                        match icon {
                            keepass::db::Icon::BuiltIn(id) => m_entry.set_icon_builtin(*id as usize),
                            keepass::db::Icon::Custom(_) => {} 
                        }
                    }
                }
                
                let current_m_parent_id = m_entry.parent_mut().id();
                if current_m_parent_id != up_group.id() {
                    let _ = m_entry.move_to(up_group.id());
                }
            } else {
                let parent_id = up_group.id();
                if master.group(parent_id).is_some() {
                    let mut m_parent = master.group_mut(parent_id).unwrap();
                    if let Ok(mut new_m_entry) = m_parent.add_entry_with_id(e_id) {
                        new_m_entry.fields = up_entry.fields.clone();
                        new_m_entry.tags = up_entry.tags.clone();
                        new_m_entry.custom_data = up_entry.custom_data.clone();
                        new_m_entry.times = up_entry.times.clone();
                        new_m_entry.autotype = up_entry.autotype.clone();
                        if let Some(icon) = up_entry.icon() {
                            match icon {
                                keepass::db::Icon::BuiltIn(id) => new_m_entry.set_icon_builtin(*id as usize),
                                keepass::db::Icon::Custom(_) => {} 
                            }
                        }
                    }
                }
            }
        }
    }

    Ok(())
}
