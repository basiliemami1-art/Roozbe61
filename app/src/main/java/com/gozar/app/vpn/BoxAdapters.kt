package com.gozar.app.vpn

import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.StringIterator

/**
 * gomobile can only hand collections across the JNI boundary as iterators, so
 * every list we return to (or read from) libbox goes through these adapters.
 */

class StringList(private val values: List<String>) : StringIterator {
    private var index = 0
    override fun len(): Int = values.size
    override fun hasNext(): Boolean = index < values.size
    override fun next(): String = values[index++]
}

class NetworkInterfaceList(
    private val values: List<NetworkInterface>,
) : NetworkInterfaceIterator {
    private var index = 0
    override fun hasNext(): Boolean = index < values.size
    override fun next(): NetworkInterface = values[index++]
}

fun StringIterator?.toList(): List<String> {
    val iterator = this ?: return emptyList()
    val out = ArrayList<String>()
    while (iterator.hasNext()) out.add(iterator.next())
    return out
}

fun RoutePrefixIterator?.toList(): List<RoutePrefix> {
    val iterator = this ?: return emptyList()
    val out = ArrayList<RoutePrefix>()
    while (iterator.hasNext()) out.add(iterator.next())
    return out
}
