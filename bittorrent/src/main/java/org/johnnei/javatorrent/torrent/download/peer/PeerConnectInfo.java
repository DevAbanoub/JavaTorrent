package org.johnnei.javatorrent.torrent.download.peer;

import java.net.InetSocketAddress;

import org.johnnei.javatorrent.torrent.download.Torrent;

/**
 * A small class which holds information about the peer which is trying to connect
 * @author Johnnei
 *
 */
public class PeerConnectInfo {

	/**
	 * If known the torrent to which this peer tries to connect
	 */
	private final Torrent torrent;

	/**
	 * The address information of this peer
	 */
	private final InetSocketAddress address;

	public PeerConnectInfo(Torrent torrent, InetSocketAddress address) {
		this.torrent = torrent;
		this.address = address;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((address == null) ? 0 : address.hashCode());
		result = prime * result + ((torrent == null) ? 0 : torrent.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (!(obj instanceof PeerConnectInfo)) {
			return false;
		}
		PeerConnectInfo other = (PeerConnectInfo) obj;
		if (address == null) {
			if (other.address != null) {
				return false;
			}
		} else if (!address.equals(other.address)) {
			return false;
		}
		if (torrent == null) {
			if (other.torrent != null) {
				return false;
			}
		} else if (!torrent.equals(other.torrent)) {
			return false;
		}
		return true;
	}

	public Torrent getTorrent() {
		return torrent;
	}

	public InetSocketAddress getAddress() {
		return address;
	}

}
