
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
	
	private int MSize = 10;
	private HashMap<DTNHost, MapTuple> mapHosts = new HashMap<DTNHost, MapTuple>();
	private HashMap<DTNHost, HashMap<DTNHost, MapTuple> > mapOfOtherHosts = 
			new HashMap<DTNHost, HashMap<DTNHost, MapTuple> >();
	
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
		
		for (Connection con : getConnections()) {
			DTNHost host = con.getOtherNode(getHost());
			MapTuple tuple = mapHosts.get(host);
			if ((tuple == null || (SimClock.getTime() - tuple.mLastEncounterTime) > 60.) && con.isUp()) {
				updateTableAndSend(host);
			}
		}
		
		List<Message> msgs = new ArrayList<Message>(getMessageCollection());
		List<Connection> connections = getConnections();
		if (connections.size() > 0 && getNrofMessages() > 0) {
			sortByQueueMode(msgs);
			for (Message m : msgs) {
				Connection conChosen = null;
				MapTuple lastEncounterWithDest = new MapTuple(new Coord(0,0), 0.);
				Boolean jumpingToArchor = (Boolean) m.getProperty("JumpingToAnArchorPoint");
				lastEncounterWithDest.mLastEncounterTime = 0;
				double distanceToDestination = Double.MAX_VALUE, lastTime = 0.;
				
				if (mapHosts.containsKey(m.getTo())) {
					lastEncounterWithDest = (MapTuple) mapHosts.get(m.getTo());
				}
				for (Connection con : connections) {
					DTNHost host = con.getOtherNode(getHost());
					if (m.getProperty("MyTable") != null) {
						startTransfer(m, con);
						break;
					}
					if (m.getTo() == host) {
							startTransfer(m, con);
					}
					if (m.getHops().contains(host)) { // evita ciclos
						continue;
					}
					if (con.isUp() && jumpingToArchor != null) {
						HashMap<DTNHost, MapTuple> mapOther = 
								(HashMap<DTNHost, MapTuple>) mapOfOtherHosts.get(host);
						if (!jumpingToArchor.booleanValue()) {
							if (mapOther != null && mapOther.containsKey(m.getTo())) {
								MapTuple tuple = mapOther.get(m.getTo());
								if ((SimClock.getTime() - tuple.mLastEncounterTime) < 2.*((SimClock.getTime() - lastEncounterWithDest.mLastEncounterTime))) {
									if (lastTime < tuple.mLastEncounterTime) {
										conChosen = con;
										System.out.println(getHost() + " : Enviar para " + m.getTo() + " por " + host);
										System.out.println(SimClock.getTime() + " <> " + lastTime);
										lastTime = tuple.mLastEncounterTime;
									}
								}
							}
						} else {
							if (mapOther != null) {
								Coord destArchor = (Coord) m.getProperty("ArchorPosition");
								assert(destArchor != null);
								double d = mahDistance(worldToSquareLattice(getHost().getLocation()), destArchor);
								if (distanceToDestination > d) {
									distanceToDestination = d;
									conChosen = con;
								}
							}
						}
					}
					
				}
				
				if (jumpingToArchor != null) {
					// se não achar algum vizinho que conheça o destino, busque o vizinho mais próximo
					if (conChosen == null) {
						double dist = Double.MAX_VALUE;
						for (Connection con : connections) {
							if (conChosen == null) {
								conChosen = con;
							} else {
								MapTuple tuple = (MapTuple) mapHosts.get(getHost());
								if (tuple != null) {
									double d = mahDistance(worldToSquareLattice(getHost().getLocation()), tuple.mLastPosition);
									if (dist > d) {
										conChosen = con;
										dist = d;
									}
								}
							}
						}
					}
					if (conChosen != null) {
						int retVal = startTransfer(m, conChosen);
						if (retVal == RCV_OK) {
							/*System.out.print("####### " + getHost() + " : enviada " + m.getId() + " para " + conChosen.getOtherNode(getHost()));
							for (DTNHost h : m.getHops()) {
								System.out.print(" ; " + h);
							}
							System.out.println("; &&& " + m.getFrom() + "=> " + m.getTo());*/
						}
					}
				}
				
			}
		}
		
		//tryAllMessagesToAllConnections();
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

	private void updateTableAndSend(DTNHost otherHost) {
		double dist = mahDistance(worldToSquareLattice(otherHost.getLocation()), worldToSquareLattice(getHost().getLocation()));
		if (dist <= 1.) { // envia somente para os vizinhos próximos
			MapTuple value = new MapTuple(worldToSquareLattice(otherHost.getLocation()), SimClock.getTime());
			mapHosts.put(otherHost, value);

			Message m = new Message(getHost(), otherHost, 
					"broadcast" + getHost() + otherHost + SimClock.getIntTime(), 1);

			m.addProperty("MyTable", mapHosts);

			super.createNewMessage(m);
		}
	}
	
	@Override
	public void changedConnection(Connection con) {
		DTNHost otherHost = con.getOtherNode(getHost());
		if (con.isUp()) {
			updateTableAndSend(otherHost);
		}
	}
	
	// Chamado sempre quando o nó quer enviar uma nova mensagem para algum outro nó na rede
	@Override 
	public boolean createNewMessage(Message m) {
		
		m.addProperty("JumpingToAnArchorPoint", new Boolean(false));
		
		return super.createNewMessage(m);
	}
	
	public double mahDistance(Coord p1, Coord p2) {
		double dx = p2.getX() - p1.getX();
		double dy = p2.getY() - p1.getY();
		return (dx*dx + dy*dy);
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
		
		HashMap<DTNHost, MapTuple> mapOtherHost = (HashMap<DTNHost, MapTuple>) incoming.getProperty("MyTable"); 
		if (mapOtherHost != null) {
			if (incoming.getTo() == getHost() && incoming.getFrom() == from) { // verifica se foi o vizinho que mandou
				mapOfOtherHosts.put(from, mapOtherHost);
				//System.out.println("Recebi tabela");
			} else {
				return null;
			}
		} else {

			Boolean jumpingToArchor = (Boolean) incoming.getProperty("JumpingToAnArchorPoint");
			Coord   archorPos       = (Coord) incoming.getProperty("ArchorPosition");
			Coord   myPos           = getHost().getLocation();
			if (jumpingToArchor != null) {
				if (!jumpingToArchor.booleanValue()) {
					MapTuple tuple = mapHosts.get(incoming.getTo());
					if (tuple != null && mahDistance(worldToSquareLattice(myPos), tuple.mLastPosition) > 1.) {
						incoming.updateProperty("JumpingToAnArchorPoint", new Boolean(true));
						if (archorPos == null) {
							incoming.addProperty("ArchorPosition", tuple.mLastPosition);
						} else {
							incoming.updateProperty("ArchorPosition", tuple.mLastPosition);
						}
					}					
				} else {
					if (mahDistance(worldToSquareLattice(myPos), archorPos) <= 1.) {
						incoming.updateProperty("JumpingToAnArchorPoint", new Boolean(false));
					}					
				}
			}
			// vizinho
			
			//incoming.updateProperty(", value)
			
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