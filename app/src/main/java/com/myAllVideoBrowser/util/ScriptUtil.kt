package com.myAllVideoBrowser.util

class ScriptUtil {

    companion object {
        const val FACEBOOK_SCRIPT = "javascript:function clickOnVideo(link, classValueName){" +
                "browser.getVideoData(link);" +
                "var element = document.getElementById(\"mInlineVideoPlayer\");" +
                "element.muted = true;" +
                "var parent = element.parentNode; " +
                "parent.removeChild(element);" +
                "parent.setAttribute('class', classValueName);}" +
                "function getVideoLink(){" +
                "try{var items = document.getElementsByTagName(\"div\");" +
                "for(i = 0; i < items.length; i++){" +
                "if(items[i].getAttribute(\"data-sigil\") == \"inlineVideo\"){" +
                "var classValueName = items[i].getAttribute(\"class\");" +
                "var jsonString = items[i].getAttribute(\"data-store\");" +
                "var obj = JSON && JSON.parse(jsonString) || $.parseJSON(jsonString);" +
                "var videoLink = obj.src;" +
                "var videoName = obj.videoID;" +
                "items[i].setAttribute('onclick', \"clickOnVideo('\"+videoLink+\"','\"+classValueName+\"')\");}}" +
                "var links = document.getElementsByTagName(\"a\");" +
                "for(i = 0; i < links.length; i++){" +
                "if(links[ i ].hasAttribute(\"data-store\")){" +
                "var jsonString = links[i].getAttribute(\"data-store\");" +
                "var obj = JSON && JSON.parse(jsonString) || $.parseJSON(jsonString);" +
                "var videoName = obj.videoID;" +
                "var videoLink = links[i].getAttribute(\"href\");" +
                "var res = videoLink.split(\"src=\");" +
                "var myLink = res[1];" +
                "links[i].parentNode.setAttribute('onclick', \"browser.getVideoData('\"+myLink+\"')\");" +
                "while (links[i].firstChild){" +
                "links[i].parentNode.insertBefore(links[i].firstChild," +
                "links[i]);}" +
                "links[i].parentNode.removeChild(links[i]);}}}catch(e){}}" +
                "getVideoLink();"
    }
}