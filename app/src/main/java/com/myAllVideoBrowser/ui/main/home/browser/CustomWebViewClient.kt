package com.myAllVideoBrowser.ui.main.home.browser

import android.graphics.Bitmap
import android.os.Build
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.ui.main.history.HistoryViewModel
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.FaviconUtils
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.IVideoDetector
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabViewModel
import com.myAllVideoBrowser.util.CookieUtils
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.util.VideoUtils
import com.myAllVideoBrowser.util.get
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.ByteArrayInputStream
import androidx.core.net.toUri
import com.myAllVideoBrowser.ui.main.home.browser.adblocker.AdBlockEngine
import com.myAllVideoBrowser.util.AppLogger

val disableWebRtcJs = """
    (function() {
        function blockWebRTC(win) {
            try {
                // Stub RTCPeerConnection as a constructor that throws NotSupportedError
                const DummyRTCPeerConnection = function() {
                    throw new win.DOMException("RTCPeerConnection not supported", "NotSupportedError");
                };
                DummyRTCPeerConnection.prototype = {};
                
                win.RTCPeerConnection = DummyRTCPeerConnection;
                win.webkitRTCPeerConnection = DummyRTCPeerConnection;
                win.mozRTCPeerConnection = DummyRTCPeerConnection;
                
                win.RTCSessionDescription = function() {};
                win.webkitRTCSessionDescription = function() {};
                win.mozRTCSessionDescription = function() {};
                
                win.RTCIceCandidate = function() {};
                win.webkitRTCIceCandidate = function() {};
                win.mozRTCIceCandidate = function() {};
            } catch (e) {}
        }

        // 1. Block on main window
        blockWebRTC(window);
    })();
""".trimIndent()

val injectJsInterceptor = """
    (function() {
    function hookPostInterceptor(win) {
        try {
            if (!win || win.__postInterceptorInstalled) return;
            win.__postInterceptorInstalled = true;

            if (win.XMLHttpRequest) {
                var oldOpen = win.XMLHttpRequest.prototype.open;
                win.XMLHttpRequest.prototype.open = function(method, url) {
                    this._url = url ? url.toString() : '';
                    this._method = (method || 'GET').toUpperCase();
                    return oldOpen.apply(this, arguments);
                };

                var oldSend = win.XMLHttpRequest.prototype.send;
                win.XMLHttpRequest.prototype.send = function(body) {
                    var url = this._url || '';
                    var method = this._method || 'GET';

                    var bridge = win.AndroidBridge || window.AndroidBridge;
                    if (method === 'POST' && bridge && bridge.shouldInterceptPost) {
                        if (bridge.shouldInterceptPost(url, body || "")) {
                            this.abort();
                            return;
                        }
                    }
                    return oldSend.apply(this, arguments);
                };
            }

            if (win.fetch) {
                var originalFetch = win.fetch;
                win.fetch = function(input, init) {
                    init = init || {};
                    var url = '';
                    if (typeof input === 'string') {
                        url = input;
                    } else if (input && typeof input === 'object') {
                        if (input.url) {
                            url = input.url;
                        } else if (typeof input.toString === 'function') {
                            url = input.toString();
                        }
                    } else if (input) {
                        url = String(input);
                    }

                    var method = (init.method || (typeof input === 'object' && input.method) || 'GET').toUpperCase();
                    var bridge = win.AndroidBridge || window.AndroidBridge;
                    if (method === 'POST' && bridge && bridge.shouldInterceptPost) {
                        if (bridge.shouldInterceptPost(url, init.body || "")) {
                            return Promise.resolve(new win.Response('', { status: 200 }));
                        }
                    }

                    return originalFetch.apply(this, arguments);
                };
            }
        } catch (e) {}
    }

    hookPostInterceptor(window);
    try {
        if (window.frames && window.frames.length) {
            for (var i = 0; i < window.frames.length; i++) {
                try {
                    hookPostInterceptor(window.frames[i]);
                } catch (e) {}
            }
        }
    } catch (e) {}
})();
""".trimIndent()

val injectJsDetector = """
    (function() {
    function hookDetector(win) {
        try {
            if (!win || win.__videoDetectorInstalled) return;
            win.__videoDetectorInstalled = true;

            // ===================== Helpers =====================

            function headersToObject(headers) {
                var obj = {};
                if (!headers) return obj;

                if (typeof headers.forEach === 'function') {
                    headers.forEach(function(value, key) {
                        obj[key] = value;
                    });
                    return obj;
                }

                if (typeof headers === 'object') {
                    for (var k in headers) {
                        if (Object.prototype.hasOwnProperty.call(headers, k)) {
                            obj[k] = headers[k];
                        }
                    }
                }
                return obj;
            }

            function getXhrResponseHeaders(xhr) {
                var obj = {};
                try {
                    var raw = xhr.getAllResponseHeaders();
                    if (raw) {
                        var lines = raw.trim().split(/[\r\n]+/);
                        for (var i = 0; i < lines.length; i++) {
                            var line = lines[i];
                            var parts = line.split(':');
                            var key = parts.shift().trim();
                            var value = parts.join(':').trim();
                            if (key) {
                                obj[key] = value;
                            }
                        }
                    }
                } catch (e) {}
                return obj;
            }

            function getHeaderPayload(customReqHeaders, respHeaders, interceptorType, extraContext) {
                var req = {};
                var resp = {};

                if (customReqHeaders) {
                    for (var k in customReqHeaders) {
                        if (Object.prototype.hasOwnProperty.call(customReqHeaders, k)) {
                            req[k] = customReqHeaders[k];
                        }
                    }
                }

                function setIfMissing(name, value) {
                    if (value == null || value === '') return;
                    var lower = name.toLowerCase();
                    for (var existing in req) {
                        if (existing.toLowerCase() === lower) return;
                    }
                    req[name] = value;
                }

                try {
                    var referer = null;
                    if (extraContext && extraContext.referer) {
                        referer = extraContext.referer;
                    } else if (win.location && win.location.href && win.location.href !== 'about:blank') {
                        referer = win.location.href;
                    } else if (document && document.referrer) {
                        referer = document.referrer;
                    }
                    setIfMissing("Referer", referer);

                    setIfMissing("User-Agent", win.navigator && win.navigator.userAgent);

                    var lang = null;
                    if (win.navigator) {
                        lang = (win.navigator.languages && win.navigator.languages.length > 0)
                            ? win.navigator.languages.join(',')
                            : (win.navigator.language || win.navigator.userLanguage);
                    }
                    setIfMissing("Accept-Language", lang);

                    if (win.location && win.location.origin && win.location.origin !== 'null') {
                        setIfMissing("Origin", win.location.origin);
                    }

                    if (document && document.cookie) {
                        setIfMissing("Cookie", document.cookie);
                    }

                    setIfMissing("Accept", "*/*");

                    var dest = "empty";
                    var mode = "cors";
                    var site = "same-origin";
                    var user = "?1";

                    if (extraContext) {
                        if (extraContext.dest) dest = extraContext.dest;
                        if (extraContext.mode) mode = extraContext.mode;
                        if (extraContext.site) site = extraContext.site;
                    } else {
                        var url = (extraContext && extraContext.url) || "";
                        var lowerUrl = (url || "").toLowerCase();

                        if (interceptorType === "hls.js" || interceptorType === "shaka" || interceptorType === "dash.js" ||
                            lowerUrl.indexOf(".m3u8") !== -1 || lowerUrl.indexOf(".mpd") !== -1 ||
                            lowerUrl.indexOf("manifest") !== -1) {
                            dest = "empty";
                            mode = "cors";
                        } else if (lowerUrl.match(/\.(mp4|webm|m4v|mov|mkv|ts|m4s|m4a|aac|mp3)(\?|$)/)) {
                            dest = "video";
                            mode = "no-cors";
                        }
                    }

                    try {
                        if (referer && win.location && win.location.origin) {
                            var refOrigin = null;
                            try {
                                refOrigin = new URL(referer).origin;
                            } catch (e) {}
                            if (refOrigin) {
                                site = (refOrigin === win.location.origin) ? "same-origin" : "cross-site";
                            }
                        }
                    } catch (e) {}

                    setIfMissing("Sec-Fetch-Dest", dest);
                    setIfMissing("Sec-Fetch-Mode", mode);
                    setIfMissing("Sec-Fetch-Site", site);
                    setIfMissing("Sec-Fetch-User", user);

                } catch (e) {}

                if (respHeaders) {
                    for (var rk in respHeaders) {
                        if (Object.prototype.hasOwnProperty.call(respHeaders, rk)) {
                            resp[rk] = respHeaders[rk];
                        }
                    }
                }

                return {
                    type: interceptorType || "unknown",
                    request: req,
                    response: resp
                };
            }

            function notifyAndroid(url, headersObj, body) {
                try {
                    var bridge = win.AndroidBridge || window.AndroidBridge;
                    if (bridge && bridge.onManifest) {
                        bridge.onManifest(
                            url,
                            JSON.stringify(headersObj || {}),
                            body || ''
                        );
                    }
                } catch (e) {}
            }

            function notifyAndroidVideo(url, headersObj) {
                try {
                    var bridge = win.AndroidBridge || window.AndroidBridge;
                    if (bridge && bridge.onVideoDetected) {
                        bridge.onVideoDetected(
                            url,
                            JSON.stringify(headersObj || {})
                        );
                    }
                } catch (e) {}
            }

            function notifyAndroidAudio(url, headersObj) {
                try {
                    var bridge = win.AndroidBridge || window.AndroidBridge;
                    if (bridge && bridge.onAudioDetected) {
                        bridge.onAudioDetected(
                            url,
                            JSON.stringify(headersObj || {})
                        );
                    } else if (bridge && bridge.onVideoDetected) {
                        bridge.onVideoDetected(
                            url,
                            JSON.stringify(headersObj || {})
                        );
                    }
                } catch (e) {}
            }

            function isAudioUrlOrType(url, contentType) {
                if (!url) return false;
                var u = url.toLowerCase();
                var cleanUrl = u.split('?')[0].split('#')[0];
                var hasAudioExt = cleanUrl.endsWith('.mp3')
                    || cleanUrl.endsWith('.aac')
                    || cleanUrl.endsWith('.m4a')
                    || cleanUrl.endsWith('.wav')
                    || cleanUrl.endsWith('.ogg')
                    || cleanUrl.endsWith('.flac')
                    || cleanUrl.endsWith('.m4b')
                    || cleanUrl.endsWith('.opus');

                var hasAudioContentType = false;
                if (contentType) {
                    var ct = contentType.toLowerCase();
                    hasAudioContentType = ct.indexOf('audio/') !== -1;
                }

                return hasAudioExt || hasAudioContentType;
            }

            function isVideoUrlOrType(url, contentType) {
                if (!url) return false;
                var u = url.toLowerCase();

                if (u.indexOf('.m3u8') !== -1 || u.indexOf('.mpd') !== -1 || u.indexOf('manifest') !== -1
                    || (contentType && (contentType.indexOf('mpegurl') !== -1 || contentType.indexOf('dash+xml') !== -1))) {
                    return false;
                }

                var isSegment = u.indexOf('.ts') !== -1
                    || u.indexOf('.m4s') !== -1
                    || u.indexOf('.aac') !== -1
                    || u.indexOf('seg-') !== -1;

                if (isSegment) return false;

                var cleanUrl = u.split('?')[0].split('#')[0];
                var hasVideoExt = cleanUrl.endsWith('.mp4')
                    || cleanUrl.endsWith('.webm')
                    || cleanUrl.endsWith('.ogv')
                    || cleanUrl.endsWith('.mov')
                    || cleanUrl.endsWith('.m4v')
                    || cleanUrl.endsWith('.mkv')
                    || cleanUrl.endsWith('.avi');

                var hasVideoContentType = false;
                if (contentType) {
                    var ct = contentType.toLowerCase();
                    var isTransportStream = ct.indexOf('mp2t') !== -1 || ct.indexOf('vnd.dlna.mpeg-tts') !== -1;
                    hasVideoContentType = (ct.indexOf('video/') !== -1 && !isTransportStream)
                        || ct.indexOf('application/vnd.apple.mpegurl') !== -1
                        || ct.indexOf('application/dash+xml') !== -1;
                }

                return hasVideoExt || hasVideoContentType;
            }

            function isManifestUrlOrContent(url, contentType, text) {
                if (!url) return false;
                var lowerUrl = url.toLowerCase();
                var cleanUrl = lowerUrl.split('?')[0].split('#')[0];

                if (cleanUrl.endsWith('.m3u8') || cleanUrl.endsWith('.mpd') ||
                    lowerUrl.indexOf('.m3u8') !== -1 || lowerUrl.indexOf('.mpd') !== -1 ||
                    lowerUrl.indexOf('manifest') !== -1) {
                    return true;
                }

                if (contentType) {
                    var ct = contentType.toLowerCase();
                    if (ct.indexOf('mpegurl') !== -1 || ct.indexOf('dash+xml') !== -1) {
                        return true;
                    }
                }

                if (text) {
                    if (text.indexOf('#EXTM3U') !== -1 ||
                        text.indexOf('<MPD') !== -1 ||
                        text.toLowerCase().indexOf('<mpd') !== -1) {
                        return true;
                    }
                }

                return false;
            }

            function isSegmentUrl(url) {
                if (!url) return false;
                var u = url.toLowerCase();
                if (u.indexOf('.m3u8') !== -1 || u.indexOf('.mpd') !== -1) return false;

                return u.indexOf('.m4s') !== -1
                    || u.indexOf('.ts') !== -1
                    || u.indexOf('.m4a') !== -1
                    || u.indexOf('.aac') !== -1;
            }

            function checkUrlOrHeadersAndNotify(url, headersPayload) {
                if (!url) return;
                var respHeaders = headersPayload.response || {};
                var contentType = respHeaders['content-type'] || respHeaders['Content-Type'] || '';

                if (isManifestUrlOrContent(url, contentType, null)) {
                    console.log('MANIFEST_MATCHED: ' + url);
                    notifyAndroid(url, headersPayload, '');
                    return;
                }

                if (isVideoUrlOrType(url, contentType)) {
                    console.log('VIDEO_MATCHED: ' + url);
                    notifyAndroidVideo(url, headersPayload);
                } else if (isAudioUrlOrType(url, contentType)) {
                    console.log('AUDIO_MATCHED: ' + url);
                    notifyAndroidAudio(url, headersPayload);
                }
            }

            function getXhrResponseText(xhr) {
                try {
                    if (!xhr.responseType || xhr.responseType === 'text' || xhr.responseType === '') {
                        return xhr.responseText || '';
                    }
                    if (xhr.responseType === 'arraybuffer' && xhr.response) {
                        return new TextDecoder("utf-8").decode(xhr.response);
                    }
                } catch (e) {}
                return '';
            }

            function checkContentAndNotify(url, headersPayload, text) {
                if (!url) return;

                var respHeaders = headersPayload.response || {};
                var contentType = respHeaders['content-type'] || respHeaders['Content-Type'] || '';

                if (isManifestUrlOrContent(url, contentType, text)) {
                    console.log('MANIFEST_MATCHED: ' + url);
                    notifyAndroid(url, headersPayload, text || '');
                    return;
                }

                if (isSegmentUrl(url)) return;

                if (contentType.indexOf('video/') !== -1 || contentType.indexOf('audio/') !== -1) {
                    return;
                }

                checkUrlOrHeadersAndNotify(url, headersPayload);
            }

            // ===================== XMLHttpRequest Detector =====================
            if (win.XMLHttpRequest) {
                var oldOpen = win.XMLHttpRequest.prototype.open;
                win.XMLHttpRequest.prototype.open = function(method, url) {
                    this._url = url ? url.toString() : '';
                    this._headers = {};
                    return oldOpen.apply(this, arguments);
                };

                var oldSetRequestHeader = win.XMLHttpRequest.prototype.setRequestHeader;
                win.XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
                    if (!this._headers) this._headers = {};
                    this._headers[name] = value;
                    return oldSetRequestHeader.apply(this, arguments);
                };

                var oldSend = win.XMLHttpRequest.prototype.send;
                win.XMLHttpRequest.prototype.send = function(body) {
                    var self = this;

                    function processXhrResponse() {
                        if (self.readyState >= 2 && !self._headersChecked) {
                            self._headersChecked = true;
                            try {
                                var finalUrl = self._url || self.responseURL || '';
                                var respHeaders = getXhrResponseHeaders(self);
                                var headerPayload = getHeaderPayload(
                                    self._headers,
                                    respHeaders,
                                    "xhr",
                                    { url: finalUrl }
                                );
                                checkUrlOrHeadersAndNotify(finalUrl, headerPayload);
                            } catch (e) {}
                        }
                        if (self.readyState === 4) {
                            try {
                                var finalUrl = self._url || self.responseURL || '';
                                var text = getXhrResponseText(self);
                                var respHeaders = getXhrResponseHeaders(self);
                                var headerPayload = getHeaderPayload(
                                    self._headers,
                                    respHeaders,
                                    "xhr",
                                    { url: finalUrl }
                                );
                                checkContentAndNotify(finalUrl, headerPayload, text);
                            } catch (e) {
                                console.log('XHR_ERROR: ' + e);
                            }
                        }
                    }

                    self.addEventListener('readystatechange', processXhrResponse);
                    self.addEventListener('load', processXhrResponse);

                    var originalOnReadyStateChange = self.onreadystatechange;
                    if (originalOnReadyStateChange) {
                        self.onreadystatechange = function() {
                            processXhrResponse();
                            return originalOnReadyStateChange.apply(this, arguments);
                        };
                    }

                    return oldSend.apply(this, arguments);
                };
            }

            // ===================== fetch Detector =====================
            if (win.fetch) {
                var originalFetch = win.fetch;
                win.fetch = function(input, init) {
                    init = init || {};
                    var url = '';
                    var reqHeaders = {};

                    if (typeof input === 'string') {
                        url = input;
                    } else if (input && typeof input === 'object') {
                        if (input.url) {
                            url = input.url;
                        } else if (typeof input.toString === 'function') {
                            url = input.toString();
                        }
                        if (input.headers) {
                            reqHeaders = headersToObject(input.headers);
                        }
                    } else if (input) {
                        url = String(input);
                    }

                    if (init.headers) {
                        var extra = headersToObject(init.headers);
                        for (var k in extra) {
                            reqHeaders[k] = extra[k];
                        }
                    }

                    return originalFetch.apply(this, arguments).then(function(response) {
                        try {
                            if (response) {
                                var finalUrl = response.url || url;
                                var respHeaders = headersToObject(response.headers);
                                var headerPayload = getHeaderPayload(
                                    reqHeaders,
                                    respHeaders,
                                    "fetch",
                                    { url: finalUrl }
                                );
                                checkUrlOrHeadersAndNotify(finalUrl, headerPayload);

                                if (response.clone) {
                                    response.clone().text().then(function(text) {
                                        checkContentAndNotify(finalUrl, headerPayload, text);
                                    }).catch(function() {});
                                }
                            }
                        } catch (e) {}
                        return response;
                    });
                };
            }

            // ===================== hls.js =====================
            function applyHlsHooks(HlsClass) {
                if (!HlsClass || HlsClass.__hlsHooked) return;
                HlsClass.__hlsHooked = true;

                var origLoadSource = HlsClass.prototype ? HlsClass.prototype.loadSource : null;
                if (origLoadSource) {
                    HlsClass.prototype.loadSource = function(url) {
                        console.log('HLS_LOAD_SOURCE: ' + url);
                        if (url) {
                            notifyAndroid(url, getHeaderPayload(null, null, "hls.js", {
                                url: url,
                                dest: "empty",
                                mode: "cors"
                            }), '');
                        }
                        return origLoadSource.apply(this, arguments);
                    };
                }

                if (HlsClass.DefaultConfig && HlsClass.DefaultConfig.loader) {
                    var OrigLoader = HlsClass.DefaultConfig.loader;
                    function HookedHlsLoader(config) {
                        var loaderInstance = new OrigLoader(config);
                        var origLoad = loaderInstance.load;
                        if (origLoad) {
                            loaderInstance.load = function(context, config, callbacks) {
                                var origOnSuccess = callbacks ? callbacks.onSuccess : null;
                                if (callbacks && origOnSuccess) {
                                    callbacks.onSuccess = function(response, stats, context, networkDetails) {
                                        try {
                                            var url = (context && context.url) || (response && response.url) || '';
                                            var data = response ? response.data : null;
                                            var text = '';
                                            if (typeof data === 'string') {
                                                text = data;
                                            } else if (data && data instanceof ArrayBuffer) {
                                                text = new TextDecoder("utf-8").decode(data);
                                            }
                                            checkContentAndNotify(url, getHeaderPayload(null, null, "hls.js", {
                                                url: url,
                                                dest: "empty",
                                                mode: "cors"
                                            }), text);
                                        } catch (e) {}
                                        return origOnSuccess.apply(this, arguments);
                                    };
                                }
                                return origLoad.apply(this, arguments);
                            };
                        }
                        return loaderInstance;
                    }
                    HookedHlsLoader.prototype = OrigLoader.prototype;
                    HlsClass.DefaultConfig.loader = HookedHlsLoader;
                }
            }

            if (win.Hls) {
                applyHlsHooks(win.Hls);
            } else {
                var _hlsVal = win.Hls;
                try {
                    Object.defineProperty(win, 'Hls', {
                        get: function() { return _hlsVal; },
                        set: function(val) {
                            _hlsVal = val;
                            if (val) applyHlsHooks(val);
                        },
                        configurable: true,
                        enumerable: true
                    });
                } catch (e) {}
            }

            // ===================== Shaka Player =====================
            function applyShakaHooks(shakaObj) {
                if (!shakaObj || shakaObj.__shakaHooked) return;
                shakaObj.__shakaHooked = true;

                if (shakaObj.Player && shakaObj.Player.prototype) {
                    var origShakaLoad = shakaObj.Player.prototype.load;
                    if (origShakaLoad) {
                        shakaObj.Player.prototype.load = function(assetUri) {
                            console.log('SHAKA_LOAD_MANIFEST: ' + assetUri);
                            if (assetUri) {
                                notifyAndroid(assetUri, getHeaderPayload(null, null, "shaka", {
                                    url: assetUri,
                                    dest: "empty",
                                    mode: "cors"
                                }), '');
                            }
                            return origShakaLoad.apply(this, arguments);
                        };
                    }
                }
            }

            if (win.shaka) {
                applyShakaHooks(win.shaka);
            } else {
                var _shakaVal = win.shaka;
                try {
                    Object.defineProperty(win, 'shaka', {
                        get: function() { return _shakaVal; },
                        set: function(val) {
                            _shakaVal = val;
                            if (val) applyShakaHooks(val);
                        },
                        configurable: true,
                        enumerable: true
                    });
                } catch (e) {}
            }

            // ===================== Dash.js =====================
            function applyDashHooks(dashObj) {
                if (!dashObj || dashObj.__dashHooked) return;
                dashObj.__dashHooked = true;

                if (dashObj.MediaPlayer && dashObj.MediaPlayer.prototype) {
                    var origAttachSource = dashObj.MediaPlayer.prototype.attachSource;
                    if (origAttachSource) {
                        dashObj.MediaPlayer.prototype.attachSource = function(url) {
                            console.log('DASH_LOAD_MANIFEST: ' + url);
                            if (url) {
                                notifyAndroid(url, getHeaderPayload(null, null, "dash.js", {
                                    url: url,
                                    dest: "empty",
                                    mode: "cors"
                                }), '');
                            }
                            return origAttachSource.apply(this, arguments);
                        };
                    }
                }
            }

            if (win.dashjs) {
                applyDashHooks(win.dashjs);
            } else {
                var _dashVal = win.dashjs;
                try {
                    Object.defineProperty(win, 'dashjs', {
                        get: function() { return _dashVal; },
                        set: function(val) {
                            _dashVal = val;
                            if (val) applyDashHooks(val);
                        },
                        configurable: true,
                        enumerable: true
                    });
                } catch (e) {}
            }

            console.log('Video detector installed on window');
        } catch (e) {
            console.log('Detector install error: ' + e);
        }
    }

    hookDetector(window);
    try {
        if (window.frames && window.frames.length) {
            for (var i = 0; i < window.frames.length; i++) {
                try {
                    hookDetector(window.frames[i]);
                } catch (e) {}
            }
        }
    } catch (e) {}
})();
""".trimIndent()

enum class ContentType {
    M3U8,
    MPD,
    VIDEO,
    AUDIO,
    OTHER
}

class CustomWebViewClient(
    private val tabViewModel: WebTabViewModel,
    private val settingsModel: SettingsViewModel,
    private val videoDetectionModel: IVideoDetector,
    private val historyModel: HistoryViewModel,
    private val okHttpProxyClient: OkHttpProxyClient,
    private val updateTabEvent: SingleLiveEvent<WebTab>,
    private val pageTabProvider: PageTabProvider,
    private val proxyController: CustomProxyController,
    private val adBlockEngine: AdBlockEngine
) : WebViewClient() {
    var videoAlert: MaterialAlertDialogBuilder? = null
    private var lastSavedHistoryUrl: String = ""
    private var lastSavedTitleHistory: String = ""
    private var lastRegularCheckUrl = ""
    private val regularJobsStorage: MutableMap<String, List<Disposable>> = mutableMapOf()
    private var approvedUrl: String? = null

    companion object {
        fun emptyResponse(): WebResourceResponse {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                ByteArrayInputStream("".toByteArray())
            )
        }

        fun isBotChallengeOrCaptcha(url: String): Boolean {
            val lowerUrl = url.lowercase()
            return lowerUrl.contains(".within.website/x/cmd/anubis") ||
                    lowerUrl.contains("/challenges.cloudflare.com/") ||
                    lowerUrl.contains("/cdn-cgi/challenge-platform/") ||
                    // Google reCAPTCHA & Google bot challenges / CAPTCHAs
                    lowerUrl.contains("/recaptcha") ||
                    lowerUrl.contains("recaptcha.net") ||
                    lowerUrl.contains("gstatic.com/recaptcha") ||
                    lowerUrl.contains("google.com/recaptcha") ||
                    lowerUrl.contains("google.com/sorry") ||
                    lowerUrl.contains("google.com/captcha") ||
                    lowerUrl.contains("google.com/botguard") ||
                    lowerUrl.contains("accounts.google.com/signin/v2/challenge") ||
                    lowerUrl.contains("accounts.google.com/v3/signin/challenge") ||
                    // Other common CAPTCHAs and bot challenges
                    lowerUrl.contains("hcaptcha.com") ||
                    lowerUrl.contains("/hcaptcha") ||
                    lowerUrl.contains("arkoselabs.com") ||
                    lowerUrl.contains("funcaptcha.com") ||
                    // Facebook checkpoint & identity verification
                    lowerUrl.contains("facebook.com/checkpoint") ||
                    lowerUrl.contains("facebook.com/login/checkpoint") ||
                    lowerUrl.contains("facebook.com/identity") ||
                    lowerUrl.contains("facebook.com/confirm_code") ||
                    lowerUrl.contains("facebook.com/two_step_verification")
        }
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        val viewTitle = view?.title
        val title = tabViewModel.currentTitle.get()
        val userAgent = view?.settings?.userAgentString ?: tabViewModel.userAgent.get()

        if (url != null && lastSavedHistoryUrl != url) {
            historyModel.viewModelScope.launch(historyModel.executorSingleHistory) {
                val faviconUrl = FaviconUtils.getFaviconUrl(url)
                saveUrlToHistory(url, faviconUrl, viewTitle ?: title)

                videoDetectionModel.onStartPage(
                    url,
                    userAgent
                        ?: BrowserFragment.MOBILE_USER_AGENT
                )
                tabViewModel.onUpdateVisitedHistory(
                    url,
                    title,
                    userAgent
                )
            }
        }
        super.doUpdateVisitedHistory(view, url, isReload)
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?
    ) {
        if (proxyController.getCurrentRunningProxy().host == host) {
            val creds = proxyController.getProxyCredentials()

            if (creds.first.isNotEmpty() || creds.second.isNotEmpty()) {
                handler?.proceed(creds.first, creds.second)
            }
        }
        super.onReceivedHttpAuthRequest(view, handler, host, realm)
    }

    override fun shouldInterceptRequest(
        view: WebView?, request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request?.url == null) {
            return super.shouldInterceptRequest(view, request)
        }

        val url = request.url.toString()

        if (isBotChallengeOrCaptcha(url)) {
            return super.shouldInterceptRequest(view, request)
        }

        val headers = request.requestHeaders ?: emptyMap()
        val acceptHeader =
            headers.entries.find { it.key.equals("Accept", ignoreCase = true) }?.value ?: ""
        val requestedWith =
            headers.entries.find { it.key.equals("X-Requested-With", ignoreCase = true) }?.value
        val lowerUrl = url.lowercase()

        val resourceType = when {
            request.isForMainFrame -> "main_frame"
            lowerUrl.contains(".js") || acceptHeader.contains("javascript") -> "script"
            lowerUrl.contains(".css") || acceptHeader.contains("css") -> "stylesheet"
            lowerUrl.contains(".png") || lowerUrl.contains(".jpg") || lowerUrl.contains(".jpeg") ||
                    lowerUrl.contains(".gif") || lowerUrl.contains(".webp") || lowerUrl.contains(".svg") ||
                    acceptHeader.contains("image") -> "image"

            lowerUrl.contains(".mp4") || lowerUrl.contains(".m3u8") || lowerUrl.contains(".mpd") ||
                    lowerUrl.contains(".webm") || lowerUrl.contains(".mp3") || lowerUrl.contains(".m4a") -> "media"

            requestedWith != null -> "xmlhttprequest"
            acceptHeader.contains("html") -> "subdocument"
            else -> "other"
        }

        if (settingsModel.isAdBlockOn.get()) {
            val tabInputUrl = tabViewModel.tabUrlInput.get() ?: ""
            val sourceUrl = if (tabInputUrl.startsWith("http")) tabInputUrl else url
            val isAd = adBlockEngine.isAd(
                url,
                sourceUrl,
                resourceType
            )

            if (isAd) {
                AppLogger.d("AdBlock (GET/Resource): Blocked $url")
                return emptyResponse()
            }
        }


        val isCheckM3u8 = settingsModel.isCheckIfEveryRequestOnM3u8.get()
        val isCheckOnMp4 = settingsModel.getIsCheckEveryRequestOnMp4Video().get()
        val isCheckOnAudio = settingsModel.isCheckOnAudio.get()
        val isExperimentalDetectionOn = settingsModel.isUseExperimentalDetection.get()

        if ((isCheckOnMp4 || isCheckM3u8 || isCheckOnAudio) && !isExperimentalDetectionOn) {
            val requestWithCookies = request.let { resourceRequest ->
                try {
                    CookieUtils.webResourceRequestToOkHttpRequest(resourceRequest)
                } catch (_: Throwable) {
                    null
                }
            }

            val contentType =
                VideoUtils.getContentTypeByUrl(url, requestWithCookies?.headers, okHttpProxyClient)
            val isInterruptIntreceptedResources =
                settingsModel.isInterruptIntreceptedResources.get()
            when {

                contentType == ContentType.M3U8 || contentType == ContentType.MPD || url.contains(".m3u8") || url.contains(
                    ".mpd"
                ) || (url.contains(".txt") && url.contains("hentaihaven")) -> {
                    val isM3u8 =
                        ContentType.M3U8 == contentType || (url.contains(".txt")) || url.contains(".m3u8")
                    val isMpd = ContentType.MPD == contentType || url.contains(".mpd")

                    if (requestWithCookies != null && isCheckM3u8) {
                        videoDetectionModel.verifyLinkStatus(
                            requestWithCookies, tabViewModel.currentTitle.get(), isM3u8, isMpd
                        )
                    }
                    if (isInterruptIntreceptedResources) {
                        return emptyResponse()
                    }
                }

                else -> {
                    if ((isCheckOnMp4 || isCheckOnAudio) && contentType != ContentType.OTHER) {
                        val disposable = videoDetectionModel.checkRegularVideoOrAudio(
                            requestWithCookies,
                            isCheckOnAudio,
                            isCheckOnMp4
                        )

                        val currentUrl = tabViewModel.tabUrlInput.get() ?: ""
                        if (currentUrl != lastRegularCheckUrl) {
                            regularJobsStorage[lastRegularCheckUrl]?.forEach {
                                it.dispose()
                            }
                            regularJobsStorage.remove(lastRegularCheckUrl)
                            lastRegularCheckUrl = currentUrl
                        }
                        if (disposable != null) {
                            val overall = mutableListOf<Disposable>()
                            overall.addAll(regularJobsStorage[currentUrl]?.toList() ?: emptyList())
                            overall.add(disposable)
                            regularJobsStorage[currentUrl] = overall
                        }
                        if (isInterruptIntreceptedResources) {
                            return emptyResponse()
                        }
                    }
                }
            }
        }

        return super.shouldInterceptRequest(
            view, request
        )
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)

        videoAlert = null
        val pageTab = pageTabProvider.getPageTab(tabViewModel.thisTabIndex.get())
        val headers = pageTab.getHeaders() ?: emptyMap()
        val favi = pageTab.getFavicon() ?: view.favicon ?: favicon

        updateTabEvent.value = WebTab(
            url,
            view.title,
            favi,
            headers,
            view,
            id = pageTab.id
        )
        tabViewModel.onStartPage(url, view.title)
    }

    override fun shouldOverrideUrlLoading(view: WebView, url: WebResourceRequest): Boolean {
        val newUrl = url.url.toString()
        if (newUrl.startsWith("http") && url.isForMainFrame) {
            if (approvedUrl == newUrl) {
                approvedUrl = null
                return false
            }

            val currentUrl = view.url
            val currentHost = currentUrl?.toUri()?.host
            val newHost = url.url.host

            if (settingsModel.isAskRedirection.get() && currentHost != null && newHost != null && currentHost != newHost) {
                showRedirectionDialog(view, newUrl, newHost)
                return true
            }

            if (!tabViewModel.isTabInputFocused.get()) {
                tabViewModel.setTabTextInput(newUrl)
            }
            return false
        } else {
            return true
        }
    }

    private fun showRedirectionDialog(view: WebView, newUrl: String, newHost: String) {
        val context = view.context
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.redirection_dialog_title))
            .setMessage("${context.getString(R.string.redirection_dialog_message)} $newHost")
            .setPositiveButton(context.getString(R.string.yes)) { _, _ ->
                approvedUrl = newUrl
                view.loadUrl(newUrl)
            }
            .setNegativeButton(context.getString(R.string.no), null)
            .setNeutralButton(context.getString(R.string.dontshow)) { _, _ ->
                settingsModel.setIsAskRedirection(false)
                approvedUrl = newUrl
                view.loadUrl(newUrl)
            }
            .show()
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        tabViewModel.finishPage(url)
    }

    override fun onRenderProcessGone(
        view: WebView?, detail: RenderProcessGoneDetail?
    ): Boolean {
        val pageTab = pageTabProvider.getPageTab(tabViewModel.thisTabIndex.get())

        val webView = pageTab.getWebView()
        if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                view == webView && detail?.didCrash() == true
            } else {
                view == webView
            }
        ) {
            webView?.destroy()
            return true
        }

        return super.onRenderProcessGone(view, detail)
    }

    private suspend fun saveUrlToHistory(url: String, favicon: Any?, title: String?) {
        val isTitleEmpty = title?.trim()?.isEmpty() == true

        if (!isTitleEmpty && lastSavedTitleHistory != title && lastSavedHistoryUrl != url && url.isNotEmpty() && !url.contains(
                "about:blank"
            )
        ) {
            lastSavedHistoryUrl = url
            lastSavedTitleHistory = title ?: ""

            val faviconUrl = when (favicon) {
                is String -> favicon
                else -> FaviconUtils.getFaviconUrl(url)
            }

            yield()

            historyModel.saveHistory(
                HistoryItem(
                    url = url, faviconUrl = faviconUrl, title = title
                )
            )
        }
    }
}
