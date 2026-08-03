package com.gozar.app.data

/**
 * A place configs are pulled from.
 *
 * [url] is either a direct subscription endpoint or a Telegram channel handle
 * (`tg:channel`), which [com.gozar.app.net.SourceFetcher] resolves to the
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
 * when this list was assembled (2026-08-01); dead sources are pruned rather than
 * left in, because a failing source costs a network round trip on every refresh.
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
    )

    val all: List<SourceSpec> = subscriptions + telegram
}
