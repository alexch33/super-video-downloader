use adblock::engine::Engine;
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jboolean, jlong};
use jni::{Env, EnvUnowned};
use std::fs::File;
use std::io::{Read, Write};
use std::panic;
use std::sync::RwLock;

type SafeEngine = RwLock<Engine>;

fn safe_jstring_to_string(env: &Env<'_>, s: &JString<'_>) -> Option<String> {
    if s.is_null() {
        return None;
    }
    s.try_to_string(env).ok()
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_myAllVideoBrowser_ui_main_home_browser_adblocker_AdBlockNative_createEngineFromFiles<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    file_paths: JObjectArray<'local, JString<'local>>,
) -> jlong {
    let outcome = env.with_env(|env| -> Result<jlong, jni::errors::Error> {
        if file_paths.is_null() {
            return Ok(0);
        }
        let len = file_paths.len(env)?;
        let mut filter_set = FilterSet::new(false);

        for i in 0..len {
            let path_obj = file_paths.get_element(env, i)?;
            if let Some(path) = safe_jstring_to_string(env, &path_obj) {
                if let Ok(content) = std::fs::read_to_string(path) {
                    filter_set.add_filter_list(content, ParseOptions::default());
                }
            }
        }

        let engine = Engine::new_with_filter_set(filter_set);
        let boxed_engine = Box::new(RwLock::new(engine));
        Ok(Box::into_raw(boxed_engine) as jlong)
    });
    outcome.resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_myAllVideoBrowser_ui_main_home_browser_adblocker_AdBlockNative_serializeEngineToFile<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    engine_ptr: jlong,
    file_path: JString<'local>,
) -> jboolean {
    if engine_ptr == 0 {
        return false;
    }

    let outcome = env.with_env(|env| -> Result<jboolean, jni::errors::Error> {
        let path = match safe_jstring_to_string(env, &file_path) {
            Some(p) => p,
            None => return Ok(false),
        };
        let engine_lock = &*(engine_ptr as *const SafeEngine);
        let engine = match engine_lock.read() {
            Ok(e) => e,
            Err(_) => return Ok(false),
        };
        let bytes = engine.serialize();

        if let Ok(mut file) = File::create(path) {
            if file.write_all(&bytes).is_ok() {
                return Ok(true);
            }
        }
        Ok(false)
    });
    outcome.resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_myAllVideoBrowser_ui_main_home_browser_adblocker_AdBlockNative_createEngineFromBinaryFile<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    file_path: JString<'local>,
) -> jlong {
    let outcome = env.with_env(|env| -> Result<jlong, jni::errors::Error> {
        let path = match safe_jstring_to_string(env, &file_path) {
            Some(p) => p,
            None => return Ok(0),
        };
        let mut file = match File::open(path) {
            Ok(f) => f,
            Err(_) => return Ok(0),
        };
        let mut bytes = Vec::new();
        if file.read_to_end(&mut bytes).is_err() {
            return Ok(0);
        }

        let filter_set = FilterSet::new(false);
        let mut engine = Engine::new_with_filter_set(filter_set);
        if engine.deserialize(&bytes).is_ok() {
            let boxed_engine = Box::new(RwLock::new(engine));
            Ok(Box::into_raw(boxed_engine) as jlong)
        } else {
            Ok(0)
        }
    });
    outcome.resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_myAllVideoBrowser_ui_main_home_browser_adblocker_AdBlockNative_shouldBlock<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    engine_ptr: jlong,
    url: JString<'local>,
    source_url: JString<'local>,
    resource_type: JString<'local>,
) -> jboolean {
    if engine_ptr == 0 {
        return false;
    }

    let outcome = env.with_env(|env| -> Result<jboolean, jni::errors::Error> {
        let url_str = match safe_jstring_to_string(env, &url) {
            Some(u) => u,
            None => return Ok(false),
        };
        let source_str = safe_jstring_to_string(env, &source_url).unwrap_or_default();
        let type_str = safe_jstring_to_string(env, &resource_type).unwrap_or_else(|| "other".to_string());

        let engine_lock = &*(engine_ptr as *const SafeEngine);
        let engine = match engine_lock.read() {
            Ok(e) => e,
            Err(_) => return Ok(false),
        };

        let request = match Request::new(&url_str, &source_str, &type_str, "") {
            Ok(req) => req,
            Err(_) => return Ok(false),
        };

        Ok(engine.check_network_request(&request).should_block())
    });
    outcome.resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_myAllVideoBrowser_ui_main_home_browser_adblocker_AdBlockNative_destroyEngine<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    engine_ptr: jlong,
) {
    if engine_ptr != 0 {
        let _ = panic::catch_unwind(panic::AssertUnwindSafe(move || {
            let _ = Box::from_raw(engine_ptr as *mut SafeEngine);
        }));
    }
}
