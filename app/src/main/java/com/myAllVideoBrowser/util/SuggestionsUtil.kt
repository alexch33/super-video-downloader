package com.myAllVideoBrowser.util

import com.myAllVideoBrowser.data.local.model.Suggestion
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Flowable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class SuggestionsUtils {
    companion object {
        fun getSuggestions(okHttpClient: OkHttpClient, input: String): Flowable<List<Suggestion>> {
            return Flowable.create({ emitter ->
                val request = Request.Builder()
                    .url("https://www.google.com/complete/search?q=$input&cp=0&client=gws-wiz")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                    .use { response -> response.body?.string() }

                var s = response.toString()

                s = s.substring(s.indexOf("(") + 1, s.indexOf(")"))

                val result: ArrayList<Suggestion> = ArrayList()

                val jsn = JSONArray(s)
                for (i in 0 until jsn.length()) {
                    try {
                        val ar = JSONArray(jsn.get(i).toString())
                        for (j in 0 until ar.length()) {
                            val ar2 = JSONArray(ar.get(j).toString())

                            var tmp = ar2.get(0).toString()
                            tmp = tmp.replace(Regex("<.{1,2}>"), "")

                            result.add(Suggestion(content = tmp))
                        }
                    } catch (_: Throwable) {

                    }
                }
                emitter.onNext(result)
            }, BackpressureStrategy.LATEST)
        }
    }
}