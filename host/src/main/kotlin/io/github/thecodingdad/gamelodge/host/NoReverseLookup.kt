package io.github.thecodingdad.gamelodge.host

import java.net.InetAddress
import java.net.UnknownHostException
import java.net.spi.InetAddressResolver
import java.net.spi.InetAddressResolverProvider
import java.util.stream.Stream

/**
 * Name lookups as usual, but no reverse lookups: an address keeps its number as its name. The
 * JDK's HTTPS server asks for the name of every client before the TLS handshake, and Windows takes
 * 16 s to give up on an address without a name (NetBIOS), which held up every new connection to
 * the web admin from such a device. Registered in META-INF/services, so it serves the whole JVM.
 */
class NoReverseLookupProvider : InetAddressResolverProvider() {
    override fun get(configuration: Configuration): InetAddressResolver {
        val system = configuration.builtinResolver()
        return object : InetAddressResolver {
            override fun lookupByName(host: String, policy: InetAddressResolver.LookupPolicy): Stream<InetAddress> =
                system.lookupByName(host, policy)

            override fun lookupByAddress(addr: ByteArray): String = throw UnknownHostException()
        }
    }

    override fun name() = "no-reverse-lookup"
}
