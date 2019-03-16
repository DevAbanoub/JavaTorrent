package org.johnnei.javatorrent.bittorrent.tracker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.johnnei.javatorrent.TorrentClient;
import org.johnnei.javatorrent.torrent.Torrent;
import org.johnnei.javatorrent.utils.CheckedBiFunction;

public class TrackerFactory {

	private static final Logger LOGGER = LoggerFactory.getLogger(TrackerFactory.class);

	/**
	 * The cache of already created tracker instances for urls
	 */
	private final Map<String, ITracker> trackerInstances;

	private final Map<String, CheckedBiFunction<String, TorrentClient, ITracker, TrackerException>> trackerSuppliers;

	private final TorrentClient torrentClient;

	private TrackerFactory(Builder builder) {
		trackerSuppliers = builder.trackerSuppliers;
		torrentClient = builder.torrentClient;
		trackerInstances = new HashMap<>();
	}

	/**
	 * Either creates or returns the tracker implementation for the given url
	 * @param trackerUrl The url (including protocol) at which the tracker is available
	 * @return The tracker instance which handles the connection to the given url
	 *
	 * @throws IllegalArgumentException When the given tracker URL doesn't contain a protocol definition or an incomplete definition.
	 */
	public Optional<ITracker> getTrackerFor(String trackerUrl) {
		if (trackerInstances.containsKey(trackerUrl)) {
			return Optional.of(trackerInstances.get(trackerUrl));
		}

		if (!trackerUrl.contains("://")) {
			throw new IllegalArgumentException(String.format("Missing protocol definition in: %s", trackerUrl));
		}

		String[] trackerParts = trackerUrl.split("://", 2);
		final String protocol = trackerParts[0];
		if (!trackerSuppliers.containsKey(protocol)) {
			LOGGER.warn("Unsupported protocol: {}", protocol);
			return Optional.empty();
		}

		try {
			ITracker tracker = trackerSuppliers.get(protocol).apply(trackerUrl, torrentClient);
			trackerInstances.put(trackerUrl, tracker);
			return Optional.of(tracker);
		} catch (TrackerException e) {
			LOGGER.warn("Failed create new tracker for url: {}", trackerUrl, e);
			return Optional.empty();
		}
	}

	public List<ITracker> getTrackersHavingTorrent(Torrent torrent) {
		return trackerInstances.values().stream()
				.filter(tracker -> tracker.hasTorrent(torrent))
				.collect(Collectors.toList());
	}

	@Override
	public String toString() {
		StringBuilder stringBuilder = new StringBuilder("TrackerFactory[");
		stringBuilder.append("protocols=[");
		stringBuilder.append(trackerSuppliers.keySet().stream().reduce((a, b) -> a + ", " + b).orElse(""));
		stringBuilder.append("], instances=[");
		stringBuilder.append(trackerInstances.keySet().stream().reduce((a, b) -> a + ", " + b).orElse(""));
		stringBuilder.append("]]");
		return stringBuilder.toString();
	}

	public static class Builder {

		private Map<String, CheckedBiFunction<String, TorrentClient, ITracker, TrackerException>> trackerSuppliers;

		private TorrentClient torrentClient;

		public Builder() {
			trackerSuppliers = new HashMap<>();
		}

		public Builder registerProtocol(String protocol, CheckedBiFunction<String, TorrentClient, ITracker, TrackerException> supplier) {
			if (trackerSuppliers.containsKey(protocol)) {
				LOGGER.warn(String.format("Overriding existing %s protocol implementation", protocol));
			}

			trackerSuppliers.put(protocol, supplier);

			return this;
		}

		public Builder setTorrentClient(TorrentClient torrentClient) {
			this.torrentClient = torrentClient;
			return this;
		}

		public TrackerFactory build() {
			if (trackerSuppliers.isEmpty()) {
				throw new IllegalStateException("At least one tracker protocol must be configured.");
			}

			if (torrentClient == null) {
				throw new IllegalStateException("Torrent client must be set");
			}

			return new TrackerFactory(this);
		}

	}

}
