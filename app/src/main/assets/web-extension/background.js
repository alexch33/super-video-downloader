console.log("Background script loaded and running for request headers.");

// Listen for when a request is about to be sent. This is the correct event
// to access request headers.
browser.webRequest.onBeforeSendHeaders.addListener(
  (details) => {
    // This listener fires for every network request.
    console.log("Intercepted request headers for:", details.url); // For remote debugging

    // Send a message to the native Android application with all details.
    browser.runtime.sendNativeMessage("browser", {
      type: "REQUEST_INTERCEPTED",
      payload: {
        url: details.url,
        // The request headers for this specific request.
        // This array includes the Cookie header if it exists.
        requestHeaders: details.requestHeaders
      }
    }).catch(e => console.error(`Error sending native message for ${details.url}:`, e));
  },
  // Configuration for the listener
  {
    urls: ["<all_urls>"] // Intercept traffic from all URLs
  },
  // We need "requestHeaders" to access details.requestHeaders
  ["requestHeaders"]
);
