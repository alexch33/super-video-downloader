use jni::objects::{JClass, JString, JObject, JObjectArray};
use jni::sys::{jboolean, jlong};
use jni::JNIEnv;
use adblock::engine::Engine;
use adblock::request::Request;
use std::panic;
use std::sync::RwLock;
use std::fs::File;
use std::io::{Read, Write, BufReader};

type SafeEngine = RwLock<Engine>;

fn safe_jstring_to_string(env: &mut JNIEnv, s: JString) -> Option<String> {
    if s.is_null() { return None; }
    env.get_string(&s).ok().map(|js| js.into())
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_myAllVideoBrowser_ui_main_home_browser_adblocker_AdBlockNative_createEngineFromFiles(
    mut env: JNIEnv,
    _class: JClass,
    file_paths: JObjectArray,
) -> jlong {
    let result = panic::catch_unwind(move || {
        let len = env.get_array_length(&file_paths).ok()? as usize;
        let mut rules_list = Vec::new();

        for i in 0..len {
            let path_obj: JString = env.get_object_array_element(&file_paths, i as i32).ok()?.into();
            if let Some(path) = safe_jstring_to_string(&mut env, path_obj) {
                if let Ok(file) = File::open(path) {
                    let reader = BufReader::new(file);
                    let mut content = String::new();
                    let mut reader = reader;
                    if reader.read_to_string(&mut content).is_ok() {
                        for line in content.lines() {
                            rules_list.push(line.to_string());
                        }
                    }
                }
            }
        }

        let engine = Engine::from_rules(&rules_list, Default::default());
        let boxed_engine = Box::new(RwLock::new(engine));
        Some(Box::into_raw(boxed_engine) as jlong)
    });
    result.ok().flatten().unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_myAllVideoBrowser_ui_main_home_browser_adblocker_AdBlockNative_serializeEngineToFile(
    mut env: JNIEnv,
    _class: JClass,
    engine_ptr: jlong,
    file_path: JString,
) -> jboolean {
    if engine_ptr == 0 { return 0; }

    let result = panic::catch_unwind(move || {
        let path = safe_jstring_to_string(&mut env, file_path)?;
        let engine_lock = &*(engine_ptr as *const SafeEngine);
        let engine = engine_lock.read().ok()?;
        let bytes = engine.serialize();

        let mut file = File::create(path).ok()?;
        file.write_all(&bytes).ok()?;
        Some(1)
    });
    result.ok().flatten().unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_myAllVideoBrowser_ui_main_home_browser_adblocker_AdBlockNative_createEngineFromBinaryFile(
    mut env: JNIEnv,
    _class: JClass,
    file_path: JString,
) -> jlong {
    let result = panic::catch_unwind(move || {
        let path = safe_jstring_to_string(&mut env, file_path)?;
        let mut file = File::open(path).ok()?;
        let mut bytes = Vec::new();
        file.read_to_end(&mut bytes).ok()?;

        let mut engine = Engine::from_rules(&Vec::<String>::new(), Default::default());
        if engine.deserialize(&bytes).is_ok() {
            let boxed_engine = Box::new(RwLock::new(engine));
            Some(Box::into_raw(boxed_engine) as jlong)
        } else {
            None
        }
    });
    result.ok().flatten().unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_myAllVideoBrowser_ui_main_home_browser_adblocker_AdBlockNative_shouldBlock(
    mut env: JNIEnv,
    _class: JClass,
    engine_ptr: jlong,
    url: JString,
    source_url: JString,
    resource_type: JString,
) -> jboolean {
    if engine_ptr == 0 { return 0; }
    let panic_result = panic::catch_unwind(move || {
        if env.push_local_frame(16).is_err() { return 0; }
        let block = (|| -> Option<u8> {
            let engine_lock = &*(engine_ptr as *const SafeEngine);
            let engine = engine_lock.write().ok()?;
            
            let url_str = safe_jstring_to_string(&mut env, url)?;
            let source_str = safe_jstring_to_string(&mut env, source_url).unwrap_or_default();
            let type_str = safe_jstring_to_string(&mut env, resource_type).unwrap_or_else(|| "other".to_string());
            let request = Request::new(&url_str, &source_str, &type_str).ok()?;
            
            if engine.check_network_request(&request).matched { Some(1) } else { Some(0) }
        })();
        let final_out = block.unwrap_or(0);
        let _ = env.pop_local_frame(&JObject::null());
        final_out
    });
    match panic_result {
        Ok(v) => v as jboolean,
        Err(_) => 0,
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_myAllVideoBrowser_ui_main_home_browser_adblocker_AdBlockNative_destroyEngine(
    _env: JNIEnv,
    _class: JClass,
    engine_ptr: jlong,
) {
    if engine_ptr != 0 {
        let _ = panic::catch_unwind(move || {
            let _ = Box::from_raw(engine_ptr as *mut SafeEngine);
        });
    }
}
