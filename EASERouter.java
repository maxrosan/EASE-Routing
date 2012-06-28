
/* 
 * Copyright 2008 TKK/ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import core.*;

class MapTuple {

	double mLastEncounterTime;
	Coord mLastPosition;

	public MapTuple(Coord coord, double time) {
		mLastPosition = new Coord(coord.getX(), coord.getY());
		mLastEncounterTime = time;
	}

}

/**
 * Superclass for message routers.
 */
public class EASERouter extends ActiveRouter {

	/**
	 * Constructor. Creates a new message router based on the settings in
	 * the given Settings object.
	 * @param s The settings object
	 */
	
	private int MSize = 20;
	private HashMap<DTNHost, ArrayList<MapTuple>> mapHosts = new HashMap<DTNHost, ArrayList<MapTuple>>();
	
	public EASERouter(Settings s) {
		super(s);
	}
	
	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected EASERouter(EASERouter r) {
		super(r);
	}
	
	@Override
	protected int checkReceiving(Message m) {
		int recvCheck = super.checkReceiving(m); 
		
		if (recvCheck == RCV_OK) {
			/* don't accept a message that has already traversed this node */
			if (m.getHops().contains(getHost())) {
				recvCheck = DENIED_OLD;
			}
		}
		
		return recvCheck;
	}
			
	@Override
	public void update() {
		super.update();
		if (isTransferring() || !canStartTransfer()) {
			return; 
		}
		
		if (exchangeDeliverableMessages() != null) {
			return; 
		}
		
		tryAllMessagesToAllConnections();
	}
	
	@Override
	protected void transferDone(Connection con) {
		/* don't leave a copy for the sender */
		this.deleteMessage(con.getMessage().getId(), false);
	}

	@Override
	public MessageRouter replicate() {
		// TODO Auto-generated method stub
		return new EASERouter(this);
	}
	
	private Coord worldToSquareLattice(Coord coord) {

		assert(MSize > 0);

		Coord c = new Coord(coord.getX() / MSize, coord.getY() / MSize);
		return c;
	}
	
	@Override
	public void changedConnection(Connection con) {
		DTNHost otherHost = con.getOtherNode(getHost());
		if (con.isUp()) {
			System.out.println(getHost() + ": Connected to " + otherHost);
			MapTuple value = new MapTuple(worldToSquareLattice(otherHost.getLocation()), SimClock.getTime());
			if (!mapHosts.containsKey(otherHost)) {
				mapHosts.put(otherHost, new ArrayList<MapTuple>());
			}
			if (mapHosts.get(otherHost).size() == 500) {
				mapHosts.get(otherHost).remove(0);
			}
			mapHosts.get(otherHost).add(value);
		}
	}
	
	// Chamado sempre quando o nó quer enviar uma nova mensagem para algum outro nó na rede
	@Override 
	public boolean createNewMessage(Message m) {
		
		m.addProperty("LastEncounterWithDestination", new Double(getT(m.getTo(), SimClock.getTime())));
		m.addProperty("JumpingToAnArchorPoint", new Boolean(false));
		
		return super.createNewMessage(m);
	}
	
	public double mahDistance(Coord p1, Coord p2) {
		return (p1.getX()*p1.getX() + p2.getY()*p2.getY());
	}
	
	// Pega o último instante menor que 'tstart' em que o nó foi vizinho de 'host'
	public double getT(DTNHost host, double tstart) {
		double t = 0.;
		boolean found = false;
		Coord myPos = worldToSquareLattice(host.getLocation());
		if (mapHosts.containsKey(host)) {
			for (MapTuple tuple : mapHosts.get(host)) {
				if (tuple.mLastEncounterTime > t && tuple.mLastEncounterTime <= tstart &&  mahDistance(myPos, tuple.mLastPosition) <= 1) {
					t = tuple.mLastEncounterTime;
					found = true;
				}
			}
		}
		if (found)
			return (SimClock.getTime() - t);
		return 0.;
	}
	
	@Override
	public Message messageTransferred(String id, DTNHost from) {
		
		// o código foi retirado de messageTransferred do MessageRouter. Contém algumas alterações
		
		Message incoming = removeFromIncomingBuffer(id, from);
		boolean isFinalRecipient;
		boolean isFirstDelivery; // is this first delivered instance of the msg
		
		if (incoming == null) {
			throw new SimError("No message with ID " + id + " in the incoming "+
					"buffer of " + this.getHost());
		}
		
		// vizinho
		Boolean jumpingToAnArchorPoint = (Boolean) incoming.getProperty("JumpingToAnArchorPoint");
		if (jumpingToAnArchorPoint == null) {
			throw new SimError("Property JumpingToAnArchorPoint not found");
		}
		if (!jumpingToAnArchorPoint.booleanValue()) {
			if (incoming.getTo() != getHost()) {
				double lastEncounterTime = (Double) incoming.getProperty("LastEncounterWithDestination");
				if (getT(incoming.getTo(), SimClock.getTime()) <= (lastEncounterTime / 2.)) {
					// .. 
				}
			}
		}
		
		incoming.setReceiveTime(SimClock.getTime());
		
		isFinalRecipient = incoming.getTo() == getHost();
		isFirstDelivery = isFinalRecipient&& !isDeliveredMessage(incoming);
		
		if (!isFinalRecipient) { // not the final recipient -> put to buffer
			addToMessages(incoming, false);
		}
		else if (isFirstDelivery) {
			deliveredMessages.put(id, incoming);
		}
		
		for (MessageListener ml : this.mListeners) {
			ml.messageTransferred(incoming, from, getHost(), isFirstDelivery);
		}
		
		return incoming;
	}

}