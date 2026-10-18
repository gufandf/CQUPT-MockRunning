/*
 * 场地缓存落盘。
 *
 * AreaStore 只在内存里 —— 进程一被杀（用户划掉、系统回收），之前拉到的场地就没了，
 * 冷启动时主页地图是空的、设置页下拉框也退回「先连接拉取场地」。这里把最近一次
 * 成功拉取的结果连同 openid 存进 SharedPreferences，启动时先还原，再决定要不要打网络。
 *
 * 为什么不用文件：一个场地的多边形就几百个点，几 KB 而已，SharedPreferences 读写更省事；
 * 也不引 Gson —— org.json 是系统自带的，符合「零第三方依赖」。
 *
 * 作者：WilliTourt的蓝色大肥鱼 🐟
 */
package com.whale.campusrun

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object AreaCache {

    private const val PREFS = "campusrun_area"
    private const val KEY = "cache"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 读回上次的场地。
     *
     * @return openid 与场地列表；没有缓存、或缓存坏了都返回 null（当成第一次启动）。
     *   调用方要自己比对 openid —— 换了账号就不能拿上一个账号的场地来画。
     */
    fun load(ctx: Context): Pair<String, List<Area>>? {
        val raw = sp(ctx).getString(KEY, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val oid = o.optString("openid")
            if (oid.isEmpty()) return null
            val arr = o.optJSONArray("areas") ?: JSONArray()
            val list = ArrayList<Area>(arr.length())
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                list.add(
                    Area(
                        placeName = a.optString("name"),
                        placeCode = a.optString("code"),
                        fence = poly(a.optJSONArray("fence")),
                        excludes = exclusions(a.optJSONArray("excludes"))
                    )
                )
            }
            oid to list
        } catch (t: Throwable) {
            // 版本升级改了格式 / 数据写坏：当成没有缓存，下次成功拉取会覆盖掉
            null
        }
    }

    /** 拉取成功后落盘。失败不应该影响主流程，所以内部吞异常。 */
    fun save(ctx: Context, oid: String, list: List<Area>) {
        if (oid.isEmpty()) return
        try {
            val arr = JSONArray()
            for (a in list) {
                arr.put(
                    JSONObject()
                        .put("name", a.placeName)
                        .put("code", a.placeCode)
                        .put("fence", polyJson(a.fence))
                        .put("excludes", JSONArray().apply {
                            a.excludes.forEach { put(polyJson(it)) }
                        })
                )
            }
            val root = JSONObject()
                .put("openid", oid)
                .put("areas", arr)
            sp(ctx).edit().putString(KEY, root.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    /** 换账号时清掉，免得旧 openid 留在本机。 */
    fun clear(ctx: Context) {
        try {
            sp(ctx).edit().remove(KEY).apply()
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- JSON 形状

    private fun polyJson(points: List<DoubleArray>): JSONArray = JSONArray().apply {
        for (p in points) {
            put(JSONArray().apply {
                put(p[0])
                put(p[1])
            })
        }
    }

    /** [[lat, lon], ...] → List<DoubleArray>。 */
    private fun poly(a: JSONArray?): List<DoubleArray> {
        if (a == null) return emptyList()
        val out = ArrayList<DoubleArray>(a.length())
        for (i in 0 until a.length()) {
            val p = a.optJSONArray(i) ?: continue
            if (p.length() < 2) continue
            out.add(doubleArrayOf(p.optDouble(0), p.optDouble(1)))
        }
        return out
    }

    /** [[[lat, lon], ...], ...] → List<List<DoubleArray>>。 */
    private fun exclusions(a: JSONArray?): List<List<DoubleArray>> {
        if (a == null) return emptyList()
        val out = ArrayList<List<DoubleArray>>(a.length())
        for (i in 0 until a.length()) out.add(poly(a.optJSONArray(i)))
        return out
    }
}
