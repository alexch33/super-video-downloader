use jni::objects::{JClass, JString, JObject, JByteArray};
use jni::sys::{jboolean, jlong, jbyteArray};
use jni::JNIEnv;
use adblock::engine::Engine;
use adblock::request::Request;
use std::panic;
use std::sync::RwLock;

type SafeEngine = RwLock<Engine>;

fn safe_jstring_to_string(env: &mut JNIEnv, s: JString) -> Option<String> {
    if s.is_null() { return None; }
    env.get_string(&s).ok().map(|js| js.into())
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_myAllVideoBrowser_ui_main_home_browser_adblocker_AdBlockNative_createEngine(
    mut env: JNIEnv, // Remains mut because it is passed as &mut env below
    _class: JClass,
    rules: JString,
) -> jlong {
    let result = panic::catch_unwind(move || {
        let rules_str = safe_jstring_to_string(&mut env, rules)?;
        let rules_list: Vec<String> = rules_str.lines().map(|s| s.to_string()).collect();
        let engine = Engine::from_rules(&rules_list, Default::default());
        let boxed_engine = Box::new(RwLock::new(engine));
        Some(Box::into_raw(boxed_engine) as jlong)
    });
    result.ok().flatten().unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_myAllVideoBrowser_ui_main_home_browser_adblocker_AdBlockNative_serializeEngine(
    env: JNIEnv,
    _class: JClass,
    engine_ptr: jlong,
) -> jbyteArray {
    if engine_ptr == 0 { return std::ptr::null_mut(); }

    let result = panic::catch_unwind(move || {
        let engine_lock = unsafe { &*(engine_ptr as *const SafeEngine) };
        let engine = engine_lock.read().ok()?;
        let bytes = engine.serialize();

        env.byte_array_from_slice(&bytes)
            .map(|arr| arr.into_raw())
            .ok()
    });
    result.unwrap_or(None).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_myAllVideoBrowser_ui_main_home_browser_adblocker_AdBlockNative_createEngineFromBinary(
    env: JNIEnv,
    _class: JClass,
    data: jbyteArray,
) -> jlong {
    if data.is_null() { return 0; }

    let result = panic::catch_unwind(move || {
        let array = unsafe { JByteArray::from_raw(data) };
        let bytes = env.convert_byte_array(&array).ok()?;
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
    mut env: JNIEnv, // Remains mut because it is passed as &mut env below
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

            // Using write() to prevent the BorrowMutError from the adblock-rust internal cache
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