package com.myAllVideoBrowser.util

import com.myAllVideoBrowser.data.local.model.Suggestion
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Flowable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class SuggestionsUtils {
    companion object {
        fun getSuggestions(okHttpClient: OkHttpClient, input: String): Flowable<List<Suggestion>> {
            return Flowable.create({ emitter ->
                val request = Request.Builder()
                    .url("https://duckduckgo.com/ac/?q=$input&kl=wt-wt")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                    .use { response -> response.body.string() }

                val result: ArrayList<Suggestion> = ArrayList()

                val jsn = JSONArray(response.toString())
                for (i in 0 until jsn.length()) {
                    try {
                        val phraseObj = JSONObject(jsn.get(i).toString())
                        val phrase = phraseObj.get("phrase").toString()
                        result.add(Suggestion(content = phrase))
                    } catch (_: Throwable) {

                    }
                }
                emitter.onNext(result)
            }, BackpressureStrategy.LATEST)
        }
    }
}