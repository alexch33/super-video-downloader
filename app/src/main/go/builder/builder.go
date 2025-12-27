package main

/*
#cgo CFLAGS: -I/system/lib/
#cgo LDFLAGS: -llog
#include <jni.h>
#include <android/log.h>

// HELPER FUNCTION: This performs the JNI call in pure C, avoiding Go syntax issues.
static inline const char* get_string_utf_chars(JNIEnv* env, jstring s) {
    // This is the standard, correct C syntax for a JNI call.
    return (*env)->GetStringUTFChars(env, s, NULL);
}

// HELPER FUNCTION: This performs the corresponding release call in pure C.
static inline void release_string_utf_chars(JNIEnv* env, jstring s, const char* c) {
    (*env)->ReleaseStringUTFChars(env, s, c);
}
*/
import "C"

import (
	"sync"
	lib "github.com/2dust/AndroidLibXrayLite"
)

type dummyCallbackHandler struct{}

func (d dummyCallbackHandler) Startup() int {
	return 0 // Do nothing, just return success
}

func (d dummyCallbackHandler) Shutdown() int {
	return 0 // Do nothing, just return success
}

func (d dummyCallbackHandler) OnEmitStatus(int, string) int {
	return 0 // Do nothing, just return success
}
// =========================================================================

var (
	controller *lib.CoreController
	mu         sync.Mutex
)

func getController() *lib.CoreController {
	mu.Lock()
	defer mu.Unlock()
	if controller == nil {
		controller = lib.NewCoreController(dummyCallbackHandler{})
	}
	return controller
}

//export Java_com_myAllVideoBrowser_v2ray_V2Ray_XrayRun
func Java_com_myAllVideoBrowser_v2ray_V2Ray_XrayRun(env *C.JNIEnv, class C.jclass, jConfig C.jstring) C.jlong {
	cConfig := C.get_string_utf_chars(env, jConfig)
	defer C.release_string_utf_chars(env, jConfig, cConfig)

	goConfig := C.GoString(cConfig)
	if err := getController().StartLoop(goConfig); err != nil {
		return 1
	}
	return 0
}

//export Java_com_myAllVideoBrowser_v2ray_V2Ray_XrayStop
func Java_com_myAllVideoBrowser_v2ray_V2Ray_XrayStop(env *C.JNIEnv, class C.jclass) C.jlong {
	getController().StopLoop()
	return 0
}

//export Java_com_myAllVideoBrowser_v2ray_V2Ray_XrayIsRunning
func Java_com_myAllVideoBrowser_v2ray_V2Ray_XrayIsRunning(env *C.JNIEnv, class C.jclass) C.jlong {
	if getController().IsRunning {
		return 1
	}
	return 0
}

//export Java_com_myAllVideoBrowser_v2ray_V2Ray_XrayMeasure
func Java_com_myAllVideoBrowser_v2ray_V2Ray_XrayMeasure(env *C.JNIEnv, class C.jclass, jConfig C.jstring, jUrl C.jstring) C.jlong {
	cConfig := C.get_string_utf_chars(env, jConfig)
	defer C.release_string_utf_chars(env, jConfig, cConfig)
	goConfig := C.GoString(cConfig)

	cUrl := C.get_string_utf_chars(env, jUrl)
	defer C.release_string_utf_chars(env, jUrl, cUrl)
	goUrl := C.GoString(cUrl)

	delay, err := lib.MeasureOutboundDelay(goConfig, goUrl)
	if err != nil {
		return -1
	}
	return C.jlong(delay)
}

func main() {}
