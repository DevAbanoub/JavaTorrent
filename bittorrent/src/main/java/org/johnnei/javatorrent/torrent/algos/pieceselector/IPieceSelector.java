package org.johnnei.javatorrent.torrent.algos.pieceselector;

import java.util.Optional;

import org.johnnei.javatorrent.torrent.files.Piece;
import org.johnnei.javatorrent.torrent.peer.Peer;

@FunctionalInterface
public interface IPieceSelector {

	/**
	 * Gets the next piece to download
	 *
	 * @return The piece info of the next piece to download or <b>null</b> if no next piece is available
	 */
	Optional<Piece> getPieceForPeer(Peer p);

}
