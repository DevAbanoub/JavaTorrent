package torrent.download.peer;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.johnnei.utils.ConsoleLogger;
import org.johnnei.utils.JMath;

import torrent.download.Torrent;
import torrent.download.files.disk.DiskJobSendBlock;
import torrent.download.tracker.TrackerManager;
import torrent.network.BitTorrentSocket;
import torrent.network.ByteInputStream;
import torrent.network.ByteOutputStream;
import torrent.network.protocol.ISocket;
import torrent.protocol.BitTorrentHandshake;
import torrent.protocol.IMessage;
import torrent.protocol.MessageUtils;
import torrent.protocol.messages.MessageBitfield;
import torrent.protocol.messages.MessageKeepAlive;

public class Peer implements Comparable<Peer> {

	private Torrent torrent;
	
	/**
	 * Client information about the connected peer
	 * This will contain the requests the endpoint made of us
	 */
	private Client peerClient;
	
	/**
	 * Client information about me retrieved from the connected peer<br/>
	 * This will contain the requests we made to the endpoint 
	 */
	private Client myClient;
	
	/**
	 * The current Threads status
	 */
	private String status;

	/**
	 * The last time this connection showed any form of activity<br/>
	 * <i>Values are System.currentMillis()</i>
	 */
	private long lastActivity;

	private String clientName;
	
	/**
	 * The count of messages which are still being processed by the IOManager
	 */
	private int pendingMessages;
	
	/**
	 * The amount of errors the client made<br/>
	 * If the amount reaches 5 the client will be disconnected on the next
	 * peerCheck
	 */
	private int strikes;
	
	private Logger log;
	
	/**
	 * The pieces this peer has
	 */
	private Bitfield haveState;
	
	/**
	 * The extensions which are supported by this peer
	 */
	private Extensions extensions;
	
	/**
	 * The absolute maximum amount of requests which the peer can support<br/>
	 * Default value will be {@link Integer#MAX_VALUE}
	 */
	private int absoluteRequestLimit;
	
	/**
	 * The amount of requests we think this peer can handle at most at the same time.
	 */
	private int requestLimit;
	
	/**
	 * The bittorrent client which handles this peer's socket information and input/outputstreams
	 */
	private BitTorrentSocket socket;

	public Peer(BitTorrentSocket client, Torrent torrent) {
		this.torrent = torrent;
		this.socket = client;
		peerClient = new Client();
		myClient = new Client();
		status = "";
		clientName = "pending";
		lastActivity = System.currentTimeMillis();
		log = ConsoleLogger.createLogger("Peer", Level.INFO);
		extensions = new Extensions();
		absoluteRequestLimit = Integer.MAX_VALUE;
		haveState = new Bitfield();
	}

	public void connect() {
		setStatus("Connecting...");
		
	}

	private void sendMessage() {
		// TODO Implement this bit of code somewhere else
		if (peerClient.getQueueSize() > 0 && pendingMessages == 0) {
			Job request = peerClient.getNextJob();
			peerClient.removeJob(request);
			addToPendingMessages(1);
			torrent.addDiskJob(new DiskJobSendBlock(this, request
					.getPieceIndex(), request.getBlockIndex(), request
					.getLength()));
		}
	}

	public void checkDisconnect() {
		if (strikes >= 5) {
			socket.close();
			return;
		}
		int inactiveSeconds = (int) ((System.currentTimeMillis() - lastActivity) / 1000);
		if (inactiveSeconds > 30) {
			if (myClient.getQueueSize() > 0) { // We are not receiving a single
												// byte in the last 30(!)
												// seconds
				// Let's try (max twice) if we can wake'm up by sending them a
				// keepalive
				addStrike(2);
				getBitTorrentSocket().queueMessage(new MessageKeepAlive());
				updateLastActivity();
				return;
			} else if (torrent.getDownloadStatus() == Torrent.STATE_DOWNLOAD_METADATA) {
				updateLastActivity();
			}
		}
		if (inactiveSeconds > 60) {
			if (myClient.isInterested() && myClient.isChoked()) {
				addStrike(2);
				updateLastActivity();
			}
		}

		if (inactiveSeconds > 90) {// 1.5 Minute, We are getting close to
									// timeout D:
			if (myClient.isInterested()) {
				getBitTorrentSocket().queueMessage(new MessageKeepAlive());
			}
		}
		if (inactiveSeconds > 180) {// 3 Minutes, We've hit the timeout mark
			socket.close();
		}
	}
	
	/**
	 * Adds a download or upload job to the peer
	 * @param job
	 * @param type
	 */
	public void addJob(Job job, JobType type) {
		getClientByJobType(type).addJob(job);
	}
	
	/**
	 * Removes the download or upload job from the peer
	 * @param job
	 * @param type
	 */
	public void removeJob(Job job, JobType type) {
		getClientByJobType(type).removeJob(job);
	}
	
	/**
	 * Add a value to the pending Messages count
	 * 
	 * @param i
	 *            The count to add
	 */
	public synchronized void addToPendingMessages(int i) {
		pendingMessages += i;
	}

	@Override
	public String toString() {
		if (socket != null) {
			return socket.toString();
		} else {
			return "UNCONNECTED";
		}
	}

	public void setStatus(String s) {
		status = s;
	}

	public void setClientName(String clientName) {
		this.clientName = clientName;
	}

	public String getClientName() {
		return clientName;
	}

	public String getStatus() {
		return status;
	}

	public void updateLastActivity() {
		lastActivity = System.currentTimeMillis();
	}

	/**
	 * Gets the amount of messages waiting to be send
	 * 
	 * @return
	 */
	public boolean isWorking() {
		return getWorkQueueSize() > 0;
	}

	public int getFreeWorkTime() {
		return (getWorkQueueSize() >= myClient.getMaxRequests()) ? 0 : myClient
				.getMaxRequests() - getWorkQueueSize();
	}

	public int getWorkQueueSize() {
		return myClient.getQueueSize();
	}

	/**
	 *
	 * @return
	 * @see {@link #getRequestLimit()}
	 */
	@Deprecated
	public int getMaxWorkLoad() {
		return myClient.getMaxRequests();
	}

	@Deprecated
	public Client getClient() {
		return peerClient;
	}

	@Deprecated
	public Client getMyClient() {
		return myClient;
	}

	/**
	 * Cancels all pieces
	 */
	public void cancelAllPieces() {
		synchronized (this) {
			if (getWorkQueueSize() > 0) {
				Object[] keys = myClient.getKeySet().toArray();
				for (int i = 0; i < keys.length; i++) {
					Job job = (Job) keys[i];
					torrent.getFiles().getPiece(job.getPieceIndex())
							.reset(job.getBlockIndex());
				}
				myClient.clearJobs();
			}
		}
	}

	/**
	 * Registers that this peer has the given piece
	 * @param pieceIndex the piece to marked as "have"
	 */
	public void havePiece(int pieceIndex) {
		// TODO Some magic to correctly handle the have messages during METADATA phase
	}
	
	/**
	 * Registers that this peer has the given set of pieces
	 * @param bitfieldInfo the {@link MessageBitfield}'s bitfield
	 */
	public void havePiece(byte[] bitfieldInfo) {
		// TODO Some magic to correctly handle the have message during METADATA phase
	}
	
	/**
	 * Checks if the peer has the piece with the given index
	 * @param pieceIndex the piece to check for
	 * @return returns true when the peer has the piece otherwise false
	 */
	public boolean hasPiece(int pieceIndex) {
		return haveState.hasPiece(pieceIndex);
	}

	/**
	 * Sets the torrent
	 * 
	 * @param torrent
	 * @throws IllegalStateException
	 *             if the peer is already bound to a torrent
	 */
	public void setTorrent(Torrent torrent) throws IllegalStateException {
		if (this.torrent != null) {
			throw new IllegalStateException(
					"Peer is already bound to a torrent");
		}

		this.torrent = torrent;
	}

	public long getLastActivity() {
		return lastActivity;
	}

	public Torrent getTorrent() {
		return torrent;
	}

	@Override
	public int compareTo(Peer p) {
		int myValue = getCompareValue();
		int theirValue = p.getCompareValue();
		
		return myValue - theirValue;
	}
	
	private int getCompareValue() {
		return (getWorkQueueSize() * 5000) + haveState.countHavePieces() + socket.getDownloadRate();
	}

	/**
	 * Adds an amount of strikes to the peer
	 * 
	 * @param i
	 */
	public synchronized void addStrike(int i) {
		if (strikes + i < 0) {
			strikes = 0;
		} else {
			strikes += i;
		}
	}
	
	/**
	 * Sets the amount of requests this peer can support at most
	 * @param absoluteRequestLimit
	 */
	public void setAbsoluteRequestLimit(int absoluteRequestLimit) {
		this.absoluteRequestLimit = absoluteRequestLimit;
	}
	
	/**
	 * Sets the amount of requests we think this peer can handle properly.<br/>
	 * This amount will be limited by {@link #absoluteRequestLimit}
	 * @param requestLimit
	 */
	public void setRequestLimit(int requestLimit) {
		if (requestLimit < 0) {
			// This wouldn't make any sense
			return;
		}
		
		requestLimit = JMath.min(requestLimit, absoluteRequestLimit);
	}

	/**
	 * Sets the amount of reserved bytes for the have message caching
	 * @param bitfieldSize The amount of bytes needed to store the have messages as a bitfield
	 */
	public void setBitfieldSize(int bitfieldSize) {
		haveState.setSize(bitfieldSize);
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof Peer) {
			Peer p = (Peer) o;
			return (p.toString().equals(toString()));
		} else {
			return false;
		}
	}

	/**
	 * Adds flags to a string based on the state of a peer<br/>
	 * Possible Flags:<br/>
	 * U - Uses uTP T - Uses TCP I - Is Interested C - Is Choked
	 * 
	 * @return
	 */
	public String getFlags() {
		String flags = socket.getClass().getSimpleName().substring(0, 1);
		if (peerClient.isInterested()) {
			flags += "I";
		}
		if (peerClient.isChoked()) {
			flags += "C";
		}
		return flags;
	}
	
	public Logger getLogger() {
		return log;
	}
	
	/**
	 * Gets the extensions which are supported by this peer
	 * @return the extensions of this peer
	 */
	public Extensions getExtensions() {
		return extensions;
	}
	
	/**
	 * Gets the amount of requests we are allowed to make to this peer
	 * @return the maximum amount of concurrent requests we can make to this peer
	 */
	public int getRequestLimit() {
		return requestLimit;
	}
	
	private Client getClientByJobType(JobType type) {
		switch (type) {
		case Download:
			return myClient;
			
		case Upload:
			return peerClient;
		}
		
		return null;
	}
	
	/**
	 * Gets the amount of pieces this peer has
	 * @return returns the amount of pieces which this peer has
	 */
	public int countHavePieces() {
		return haveState.countHavePieces();
	}

	/**
	 * Gets the socket handler which handles the socket of this peer
	 * @return
	 */
	public BitTorrentSocket getBitTorrentSocket() {
		return socket;
	}

}
