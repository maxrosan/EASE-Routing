package routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import core.Connection;
import core.Coord;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;

class MapTuple {
	
	double  mLastEncounterTime;
	Coord   mLastPosition;
	
	public MapTuple(Coord coord, double time) {
		mLastPosition = new Coord(coord.getX(), coord.getY());
		mLastEncounterTime = time;
	}
	
}

public class EASERouter extends ActiveRouter {

	public static final String NROF_COPIES = "nrofCopies";
	public static final String BINARY_MODE = "binaryMode";
	public static final String M_SIZE      = "Msize";

	public static final String EASE_NS = "EASERouter";
	
	/** Message property key */
	public static final String MSG_COUNT_PROPERTY = EASE_NS + ".copies";
	public static final String MSG_ARCHOR_PROPERTY = EASE_NS + ".archor";
	
	private int initialNrofCopies;
	private boolean isBinary;
	private int MSize;
	
	private SimClock clock;
	
	private HashMap<DTNHost, MapTuple> mapHosts;
	
	public EASERouter(Settings s) {
		super(s);
		Settings eSettings = new Settings(EASE_NS);
		
		initialNrofCopies = eSettings.getInt(NROF_COPIES);
		isBinary          = eSettings.getBoolean( BINARY_MODE);
		MSize             = eSettings.getInt(M_SIZE);
		
		clock = SimClock.getInstance();
	}
	
	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected EASERouter(EASERouter r) {
		super(r);
		this.initialNrofCopies = r.initialNrofCopies;
		this.isBinary = r.isBinary;
	}
	
	@Override
	public int receiveMessage(Message m, DTNHost from) {
		return super.receiveMessage(m, from);
	}
	
	private Coord worldToSquareLattice(Coord coord) {
		
		assert(MSize > 0);
		
		Coord c = new Coord(coord.getX() / MSize, coord.getY() / MSize);
		return c;
	}
	// asdasd
	@Override
	public void changedConnection(Connection con) {
		DTNHost otherHost = con.getOtherNode(getHost());
		if (con.isUp()) {
			System.out.println(getHost() + ": Connected to " + otherHost);
			MapTuple value = new MapTuple(worldToSquareLattice(otherHost.getLocation()), clock.getTime());
			mapHosts.put(otherHost, value);
		}
	}
	
	@Override
	public Message messageTransferred(String id, DTNHost from) {
		Message msg = super.messageTransferred(id, from);
		Integer nrofCopies = (Integer)msg.getProperty(MSG_COUNT_PROPERTY);
		
		assert nrofCopies != null : "Not a SnW message: " + msg;
		
		if (isBinary) {
			/* in binary S'n'W the receiving node gets ceil(n/2) copies */
			nrofCopies = (int)Math.ceil(nrofCopies/2.0);
		}
		else {
			/* in standard S'n'W the receiving node gets only single copy */
			nrofCopies = 1;
		}
		
		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
		return msg;
	}
	
	@Override 
	public boolean createNewMessage(Message msg) {
		makeRoomForNewMessage(msg.getSize());

		msg.setTtl(this.msgTtl);
		msg.addProperty(MSG_COUNT_PROPERTY, new Integer(initialNrofCopies));
		addToMessages(msg, true);
		return true;
	}
	
	@Override
	public void update() {
		super.update();
		if (!canStartTransfer() || isTransferring()) {
			return; // nothing to transfer or is currently transferring 
		}

		/* try messages that could be delivered to final recipient */
		if (exchangeDeliverableMessages() != null) {
			return;
		}
		
		/* create a list of SAWMessages that have copies left to distribute */
		@SuppressWarnings(value = "unchecked")
		List<Message> copiesLeft = sortByQueueMode(getMessagesWithCopiesLeft());
		
		if (copiesLeft.size() > 0) {
			/* try to send those messages */
			this.tryMessagesToConnections(copiesLeft, getConnections());
		}
	}
	
	/**
	 * Creates and returns a list of messages this router is currently
	 * carrying and still has copies left to distribute (nrof copies > 1).
	 * @return A list of messages that have copies left
	 */
	private List<Message> getMessagesWithCopiesLeft() {
		List<Message> list = new ArrayList<Message>();

		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);
			assert nrofCopies != null : "SnW message " + m + " didn't have " + 
				"nrof copies property!";
			if (nrofCopies > 1) {
				list.add(m);
			}
		}
		
		return list;
	}
	
	/**
	 * Called just before a transfer is finalized (by 
	 * {@link ActiveRouter#update()}).
	 * Reduces the number of copies we have left for a message. 
	 * In binary Spray and Wait, sending host is left with floor(n/2) copies,
	 * but in standard mode, nrof copies left is reduced by one. 
	 */
	@Override
	protected void transferDone(Connection con) {
		Integer nrofCopies;
		String msgId = con.getMessage().getId();
		/* get this router's copy of the message */
		Message msg = getMessage(msgId);

		if (msg == null) { // message has been dropped from the buffer after..
			return; // ..start of transfer -> no need to reduce amount of copies
		}
		
		/* reduce the amount of copies left */
		nrofCopies = (Integer)msg.getProperty(MSG_COUNT_PROPERTY);
		if (isBinary) { 
			nrofCopies /= 2;
		}
		else {
			nrofCopies--;
		}
		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
	}
	
	@Override
	public EASERouter replicate() {
		return new EASERouter(this);
	}
}
