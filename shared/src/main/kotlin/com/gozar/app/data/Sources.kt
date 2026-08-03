package com.gozar.app.data

/**
 * A place configs are pulled from.
 *
 * [url] is either a direct subscription endpoint or a Telegram channel handle
 * (`tg:channel`), which [com.gozar.app.net.SourceLoader] resolves to the
 * public `t.me/s/<channel>` web preview.
 */
data class SourceSpec(
    val name: String,
    val url: String,
    val kind: Kind,
) {
    enum class Kind { SUBSCRIPTION, TELEGRAM }
}

/**
 * The shipped source list. Every entry here was probed and returned live configs
 * when this list was assembled (2026-08-01, re-verified 2026-08-03); dead sources
 * are pruned rather than left in, because a failing source costs a network round
 * trip on every refresh.
 *
 * A source also has to *add* something. These collectors copy heavily from one
 * another, so candidates are measured by the endpoints they contribute that no
 * other shipped source already carries — several of the largest ones turned out
 * to be 99% duplicates and are deliberately absent.
 *
 * Users can add, disable or delete any of these from the Sources screen.
 */
object DefaultSources {

    private fun sub(name: String, url: String) = SourceSpec(name, url, SourceSpec.Kind.SUBSCRIPTION)
    private fun tg(channel: String) =
        SourceSpec("@$channel", "tg:$channel", SourceSpec.Kind.TELEGRAM)

    val subscriptions: List<SourceSpec> = listOf(
        sub("Epodonios — all", "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt"),
        sub("Epodonios — vless", "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/Splitted-By-Protocol/vless.txt"),
        sub("Surfboard TGParse", "https://raw.githubusercontent.com/Surfboardv2ray/TGParse/main/configtg.txt"),
        sub("V2RayAggregator", "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt"),
        sub("ShadowsocksAggregator", "https://raw.githubusercontent.com/mahdibland/ShadowsocksAggregator/master/Eternity.txt"),
        sub("barry-far — all", "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/All_Configs_base64_Sub.txt"),
        sub("barry-far — vless", "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/vless.txt"),
        sub("MhdiTaheri Collector", "https://raw.githubusercontent.com/MhdiTaheri/V2rayCollector/main/sub/mix"),
        sub("ALIILAPRO", "https://raw.githubusercontent.com/ALIILAPRO/v2rayNG-Config/main/server.txt"),
        sub("EbraSha public list", "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/main/V2Ray-Config-By-EbraSha.txt"),
        sub("Auto proxy", "https://raw.githubusercontent.com/w1770946466/Auto_proxy/main/Long_term_subscription_num"),
        sub("Kwinshadow TG", "https://raw.githubusercontent.com/Kwinshadow/TelegramV2rayCollector/main/sublinks/mix.txt"),
        sub("NoMoreWalls", "https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list_raw.txt"),
        sub("hans-thomas", "https://raw.githubusercontent.com/hans-thomas/v2ray-subscription/master/servers.txt"),
        sub("mfuu", "https://raw.githubusercontent.com/mfuu/v2ray/master/v2ray"),
        sub("ts-sf fly", "https://raw.githubusercontent.com/ts-sf/fly/main/v2"),
        sub("ermaozi", "https://raw.githubusercontent.com/ermaozi/get_subscribe/main/subscribe/v2ray.txt"),
        sub("freefq", "https://raw.githubusercontent.com/freefq/free/master/v2"),
        sub("ripaojiedian", "https://raw.githubusercontent.com/ripaojiedian/freenode/main/sub"),
        sub("Pawdroid", "https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub"),
        // Added 2026-08-03. Large aggregators that also carry a useful number of
        // Iranian-entry nodes, which is what survives an international cut.
        sub("liMilCo", "https://raw.githubusercontent.com/liMilCo/v2r/main/all_configs.txt"),
        sub("Delta-Kronecker", "https://raw.githubusercontent.com/Delta-Kronecker/V2ray-Config/main/config/all_configs.txt"),
        sub("Freedom-V2Ray", "https://raw.githubusercontent.com/MahanKenway/Freedom-V2Ray/main/configs/mix.txt"),
        sub("DukeMehdi — lite", "https://raw.githubusercontent.com/DukeMehdi/FreeList-V2ray-Configs/main/Configs/Lite-DukeMehdi-Configs.txt"),
        // Added 2026-08-03. The percentage is the share of endpoints that no
        // other source in this list already carries, measured against the whole
        // list on that date. Together these add roughly 6,300 servers.
        sub("mheidari98 .proxy", "https://raw.githubusercontent.com/mheidari98/.proxy/main/all"), // 13.2k, 25% new
        sub("LalatinaHub Mineral", "https://raw.githubusercontent.com/LalatinaHub/Mineral/master/result/nodes"), // 2.1k, 63% new
        sub("AzadNet", "https://raw.githubusercontent.com/AzadNetCH/Clash/main/AzadNet.txt"), // 1.4k, 78% new
        sub("ndsphonemy — speed", "https://raw.githubusercontent.com/ndsphonemy/proxy-sub/main/speed.txt"), // 90% new
        sub("4n0nymou3 fetcher", "https://raw.githubusercontent.com/4n0nymou3/multi-proxy-config-fetcher/main/configs/proxy_configs.txt"),
        sub("ZywChannel", "https://raw.githubusercontent.com/ZywChannel/free/main/sub"), // small, 50% new
        // Cloudflare WARP endpoints chosen to still answer from Iran. These are
        // `warp://` links carrying no keys — a free account is registered per
        // install at connect time, since a published key would be one account
        // shared by everyone using this app.
        sub("WARP (IRCF)", "https://raw.githubusercontent.com/ircfspace/warpsub/main/export/warp"),
    )

    val telegram: List<SourceSpec> = listOf(
        tg("prrofile_purple"),
        tg("v2ray1_ng"),
        tg("v2ray_swhil"),
        tg("V2rayCollector"),
        tg("v2ray_vpn_ir"),
        tg("Outlinev2ray"),
        tg("FreeV2rays"),
        tg("vpnfail_v2ray"),
        tg("VlessConfig"),
        tg("PrivateVPNs"),
        tg("DirectVPN"),
        tg("VmessProtocol"),
        tg("vpn_ocean"),
        tg("custom_14"),
        tg("Easy_Free_VPN"),
        tg("configV2rayForFree"),
        tg("proxystore11"),
        tg("FreeVlessVpn"),
        // Added 2026-08-03; all 18 above were re-checked the same day and still
        // post configs, so none were dropped.
        tg("oneclickvpnkeys"),
        tg("v2rayNG_Matsuri"),
        tg("frev2rayng"),
        tg("ShadowsocksM"),
    )

    val all: List<SourceSpec> = subscriptions + telegram
}
