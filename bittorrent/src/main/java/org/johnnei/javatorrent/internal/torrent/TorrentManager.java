package org.johnnei.javatorrent.internal.torrent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.johnnei.javatorrent.TorrentClient;
import org.johnnei.javatorrent.internal.network.connector.NioConnectionAcceptor;
import org.johnnei.javatorrent.internal.tracker.TrackerManager;
import org.johnnei.javatorrent.torrent.Torrent;

public class TorrentManager {

	private final Object torrentListLock = new Object();

	private TorrentClient torrentClient;

	private TrackerManager trackerManager;

	private List<TorrentPair> activeTorrents;

	private NioConnectionAcceptor connectionAcceptor;

	public TorrentManager(TrackerManager trackerManager) {
		this.trackerManager = trackerManager;
		activeTorrents = new ArrayList<>();
	}

	/**
	 * Starts the connnection listener which will accept new peers
	 */
	public void start(TorrentClient torrentClient) {
		this.torrentClient = torrentClient;
	}

	/**
	 * Attempts to start a server socket to accept incoming TCP connections.
	 */
	public void enableConnectionAcceptor() {
		connectionAcceptor = new NioConnectionAcceptor(torrentClient);
	}

	/**
	 * Gracefully stops the connection processing.
	 */
	public void stop() {
		if (connectionAcceptor != null) {
			connectionAcceptor.stop();
		}
	}

	/**
	 * Registers a new torrent
	 * @param torrent The torrent to register
	 */
	public void addTorrent(Torrent torrent) {
		synchronized (torrentListLock) {
			activeTorrents.add(new TorrentPair(this, trackerManager, torrentClient, torrent));
		}
	}

	/**
	 * Removes a torrent from the list
	 * @param torrent
	 */
	public void removeTorrent(Torrent torrent) {
		synchronized (torrentListLock) {
			activeTorrents.removeIf(torrentPair -> torrentPair.getTorrent().equals(torrent));
		}
	}

	/**
	 * Shuts down the torrent processing cleanly.
	 * @param torrent The torrent to stop.
	 */
	public void shutdownTorrent(Torrent torrent) {
		Optional<TorrentPair> pair;
		synchronized (torrentListLock) {
			pair = activeTorrents.stream()
					.filter(torrentPair -> torrentPair.getTorrent().equals(torrent))
					.findAny();

			if (pair.isPresent()) {
				activeTorrents.remove(pair.get());
			}
		}

		if (pair.isPresent()) {
			pair.get().getTorrentProcessor().shutdownTorrent();
		}
	}

	/**
	 * Gets the torrent associated with the given hash.
	 * @param hash The BTIH of the torrent
	 * @return The torrent if known.
	 */
	public Optional<Torrent> getTorrent(byte[] hash) {
		return activeTorrents.stream()
				.map(TorrentPair::getTorrent)
				.filter(torrent -> Arrays.equals(torrent.getMetadata().getHash(), hash))
				.findAny();
	}

	/**
	 * Creates a copy of the list containing the torrents
	 * @return The list of torrents
	 */
	public Collection<Torrent> getTorrents() {
		synchronized (torrentListLock) {
			return activeTorrents.stream().map(TorrentPair::getTorrent).collect(Collectors.toList());
		}
	}

	private final class TorrentPair {

		private final Torrent torrent;

		private final TorrentProcessor torrentProcessor;

		TorrentPair(TorrentManager torrentManager, TrackerManager trackerManager, TorrentClient torrentClient, Torrent torrent) {
			this.torrent = torrent;
			torrentProcessor = new TorrentProcessor(torrentManager, trackerManager, torrentClient, torrent);
		}

		public Torrent getTorrent() {
			return torrent;
		}

		public TorrentProcessor getTorrentProcessor() {
			return torrentProcessor;
		}
	}

}
