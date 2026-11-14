package com.localmind.service

/**
 * GitHub 地址的**加速源**统一出口。
 *
 * ## 为什么现在才做
 *
 * 项目里原本把镜像列表抄了三份：主题包一份、字体清单一份、发布数据一份，
 * 每份的源还不一样（`ghproxy.net` / `gh-proxy.com` / `jsdelivr` 混着来）。
 * 结果是同一次网络故障下，主题能刷出来、字体一片空白、更新检查又没反应 ——
 * 用户根本没法判断到底哪坏了。
 *
 * 现在但凡要碰 GitHub，都从这里取地址：**加速源在前，直连在后**。
 * 用户的诉求很直接：国内直连 raw.githubusercontent.com 基本不通，
 * 所以直连不该再当主力，只当"加速源全挂了"的最后一条路。
 *
 * ## 为什么是"前缀拼接"而不是 API
 *
 * 这些公共加速站都是 path-prefix 代理：`https://<mirror>/<原始完整 URL>`。
 * 不依赖 API、不依赖登录，加一个源就是加一行字符串。
 *
 * ## 只对真·GitHub 地址生效
 *
 * GitHub Pages（`*.github.io`）不走这些代理（代理站不代理 Pages），
 * 所以它被视为普通域名原样返回，避免白白拼出一堆 404。
 */
object GitHubMirror {

    /** 这些 host 才需要加速。 */
    private val GITHUB_HOSTS = listOf(
        "github.com",
        "raw.githubusercontent.com",
        "objects.githubusercontent.com",
        "codeload.github.com",
        "gist.githubusercontent.com",
    )

    /**
     * 公共加速源头。
     *
     * **这份名单是真机实测出来的，不是抄的**。1.0.11 之前挂了 6 个源，其中
     * 4 个在国内已经完全不可用（DNS 被污染或直接不响应），于是每张预览图
     * 都要先白等 4 次超时才能轮到能用的那个 —— 25 张样张串行下来就是
     * 「转几分钟，最后只出一张」。
     *
     * 实测方式：手机端 `curl -m 8 -o /dev/null -w '%{http_code} %{time_total}'`
     * 打同一张 34 KB 的样张图，跑三轮取中位数。结果：
     *
     * | 源                          | 结果 | 耗时   |
     * |-----------------------------|------|--------|
     * | `isnananya.github.io` (Pages)| 200  | 0.81s  |
     * | `gh-proxy.com`               | 200  | 0.94s  |
     * | `ghproxy.net`                | 200  | 1.70s  |
     * | `ghfast.top`                 | 超时 | 8s+    |
     * | `raw.githubusercontent.com`  | 连不上 | —    |
     * | `cdn.jsdelivr.net`           | 连不上 | —    |
     * | `hub.gitmirror.com`          | 连不上 | —    |
     * | `ghproxy.cc`                 | 连不上 | —    |
     * | `gh.llkk.cc`                 | 超时 | 8s+    |
     *
     * 结论：**只留能用的两个**。宁可少一个兜底，也不要让用户等四次超时。
     */
    val PROXIES = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    fun isGitHub(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        return GITHUB_HOSTS.any { url.startsWith("https://$it/") || url.startsWith("http://$it/") }
    }

    /**
     * raw 文件还能转成 jsDelivr 的 CDN 路径 —— 国内一般比代理更快更稳。
     *
     * `raw.githubusercontent.com/<owner>/<repo>/<branch>/<path>`
     *   → `cdn.jsdelivr.net/gh/<owner>/<repo>@<branch>/<path>`
     *
     * Releases 的二进制走不了这条（jsDelivr 只索引仓库内的文件），返回 null。
     */
    private fun jsDelivr(url: String): String? {
        val m = RAW_PATTERN.find(url) ?: return null
        val (owner, repo, branch, path) = m.destructured
        return "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path"
    }

    private val RAW_PATTERN =
        Regex("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")

    /**
     * raw 文件 → 同仓库的 GitHub Pages 地址。
     *
     * `raw.githubusercontent.com/<owner>/<repo>/<branch>/<path>`
     *   → `<owner>.github.io/<repo>/<path>`
     *
     * 这是本次修复里收益最大的一条：Pages 走 Fastly CDN，**国内实测 0.8 秒**
     * 就返回，比所有前缀代理都快，而且不经过任何第三方（不存在"哪天又挂了"）。
     * 但它要求仓库开了 Pages 且从该分支根目录发布 —— 不满足时就是一次**很快的
     * 404**，比在这里卡 8 秒等超时强得多。
     *
     * 所以它排在候选第一位，但**不剥夺**后面代理源的兜底机会。
     */
    private fun pages(url: String): String? {
        val m = RAW_PATTERN.find(url) ?: return null
        val (owner, repo, _, path) = m.destructured
        return "https://$owner.github.io/$repo/$path"
    }

    /**
     * 单个地址 → 候选地址列表。**顺序即尝试顺序**：
     *
     * 1. Pages（实测最快，且不依赖第三方站）
     * 2. 能连通的加速源
     * 3. jsDelivr（曾经很好，现在国内连不上，留作最后手段 —— 它失败极快，几乎不浪费时间）
     * 4. 原生直连（国内基本不通，但留着"万一哪天恢复了"）
     *
     * 不是 GitHub 地址就原样返回。
     */
    fun mirrors(url: String): List<String> {
        if (url.isBlank()) return emptyList()
        if (!isGitHub(url)) return listOf(url)

        val out = ArrayList<String>(PROXIES.size + 3)
        pages(url)?.let { out.add(it) }
        PROXIES.forEach { out.add(it + url) }
        jsDelivr(url)?.let { out.add(it) }
        out.add(url)
        return out.distinct()
    }

    /** 一批地址 → 展开后的候选地址（保持顺序、去重）。 */
    fun expand(urls: List<String>): List<String> =
        urls.filter { it.isNotBlank() }
            .flatMap { mirrors(it) }
            .distinct()

    /** 本项目的匿名数据仓库根（主题清单 / 字体清单 / 发布信息都在这下面）。 */
    private const val DATA_RAW =
        "https://raw.githubusercontent.com/isnananya/LocalMind-Data/main/data"

    /** GitHub Pages 同源托管：不走前缀代理，但国内一般可达，留作独立一条。 */
    private const val DATA_PAGES = "https://isnananya.github.io/LocalMind-Data/data"

    /**
     * 数据清单的候选根地址，**Pages 在前、加速源居中、直连最后**。
     *
     * 主题、字体、发布信息三个服务共用这一份，免得"主题能刷、字体刷不出来"。
     * 注意 `mirrors(DATA_RAW)` 现在第一位就是 [pages] 推导出的
     * `isnananya.github.io/LocalMind-Data/data`，和 [DATA_PAGES] 是同一个值，
     * `distinct()` 会去重，所以不会白发两次请求。
     */
    val DATA_BASES: List<String> = (mirrors(DATA_RAW) + DATA_PAGES).distinct()
}
