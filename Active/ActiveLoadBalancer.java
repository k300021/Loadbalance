package net.floodlightcontroller.LoadBalancing.Active;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Date;

import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.util.HexString;
import org.openflow.util.U16;
import org.openflow.util.Unsigned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.web.AllSwitchStatisticsResource;
import net.floodlightcontroller.counter.ICounterStoreService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.loadbalancer.LoadBalancer;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.topology.NodePortTuple;
import net.floodlightcontroller.util.OFMessageDamper;
import net.floodlightcontroller.LoadBalancing.Active.ILoadbalanceRoutingService;
import net.floodlightcontroller.LoadBalancing.Active.LoadbalanceRouting.pairNodePortTuple;
import net.floodlightcontroller.staticflowentry.IStaticFlowEntryPusherService;

import org.openflow.protocol.OFType;
import org.openflow.protocol.statistics.OFPortStatisticsReply;
import org.openflow.protocol.statistics.OFPortStatisticsRequest;
import org.openflow.protocol.statistics.OFFlowStatisticsReply;
import org.openflow.protocol.statistics.OFFlowStatisticsRequest;
import org.openflow.protocol.statistics.OFStatistics;
import org.openflow.protocol.statistics.OFStatisticsType;
import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFStatisticsRequest;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Set;

import net.floodlightcontroller.packet.Ethernet;

import org.openflow.util.HexString;
import org.openflow.util.Unsigned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ActiveLoadBalancer implements IFloodlightModule,
		IOFMessageListener {
	
	int source_address;
	long flowcookie_counter=0;
	
	protected static Logger log = LoggerFactory.getLogger(LoadBalancer.class);
	
	protected IFloodlightProviderService floodlightProvider;
	protected IDeviceService deviceManager;
	protected ITopologyService topology;
	protected ILoadbalanceRoutingService routingEngine;
	protected IStaticFlowEntryPusherService sfp;
    protected ICounterStoreService counterStore;
    protected OFMessageDamper messageDamper;
    protected HashMap< Long , Route> flow_route = new HashMap< Long , Route>();
    protected HashMap< Long , Long> flow_dead = new HashMap< Long , Long>();
    
    //Copied from Forwarding with message damper routine for pushing proxy Arp 
    protected static int OFMESSAGE_DAMPER_CAPACITY = 10000; // ms. 
    protected static int OFMESSAGE_DAMPER_TIMEOUT = 250; // ms 
	protected static int LB_PRIORITY = 10235;
	protected static String LB_ETHER_TYPE = "0x800";

	protected static short FLOWMOD_DEFAULT_IDLE_TIMEOUT = 300; // in seconds
	public static short FLOWMOD_DEFAULT_HARD_TIMEOUT = 0; // infinite
	protected static Logger logger;
	protected static int pi_count=0;
	protected HashMap<pairNodePortTuple, flow> pathMap = new HashMap<pairNodePortTuple, flow>();
	
	protected class  flow{
	       public Route x;
	       public boolean y;   
	       }
	
	protected static java.util.Date utilDate = new java.util.Date();
	Date date = new Date();   // given date
	Calendar calendar = GregorianCalendar.getInstance(); // creates a new calendar instance
	
	
	//private static final String LOG_PROP = "~/Desktop";
	@Override
	public String getName() {
		return "activeLoadbalancer";
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// copy form LoadBalancer, and our active loadbalancer should run behind build-in loadbalancer 
		return (type.equals(OFType.PACKET_IN) && 
               (name.equals("topology") || 
               name.equals("devicemanager") ||
               name.equals("loadbalancer") ||
               name.equals("virtualizer")));
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// copy form LoadBalancer
		return (type.equals(OFType.PACKET_IN) && name.equals("forwarding"));
	}

	@Override
	public net.floodlightcontroller.core.IListener.Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		switch (msg.getType()) {
        case PACKET_IN:
        {
        	//System.out.printf("\n packet in\n");
            return processPacketIn(sw, (OFPacketIn)msg, cntx);
        }
        default:
            break;
		}
		return Command.CONTINUE;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		return null;
	}
	
	//copy from ForwadingBase
    public Comparator<SwitchPort> clusterIdComparator =
            new Comparator<SwitchPort>() {
                @Override
                public int compare(SwitchPort d1, SwitchPort d2) {
                    Long d1ClusterId = 
                            topology.getL2DomainId(d1.getSwitchDPID());
                    Long d2ClusterId = 
                            topology.getL2DomainId(d2.getSwitchDPID());
                    return d1ClusterId.compareTo(d2ClusterId);
                }
            };
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(ILoadbalanceRoutingService.class);
		l.add(ITopologyService.class);
		l.add(IStaticFlowEntryPusherService.class);
		l.add(ICounterStoreService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		deviceManager = context.getServiceImpl(IDeviceService.class);
		topology = context.getServiceImpl(ITopologyService.class);
		routingEngine = context.getServiceImpl(ILoadbalanceRoutingService.class);
		sfp = context.getServiceImpl(IStaticFlowEntryPusherService.class);
		counterStore = context.getServiceImpl(ICounterStoreService.class);
		messageDamper = new OFMessageDamper(OFMESSAGE_DAMPER_CAPACITY, 
                EnumSet.of(OFType.FLOW_MOD),
                OFMESSAGE_DAMPER_TIMEOUT);
		logger = LoggerFactory.getLogger(ILoadbalanceRoutingService.class);
	    logger.info(this.getName()+"\n");
		
		MyTimer thread1;
		 
    	thread1 = new MyTimer();
    	Thread t1 = new Thread(thread1, "T1");
        
        t1.start();
    
	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);

	}
	@SuppressWarnings("deprecation")
	private net.floodlightcontroller.core.IListener.Command 
	processPacketIn(IOFSwitch sw, OFPacketIn pi,
                    FloodlightContext cntx)
	{
		//System.out.printf("Now time is: "+utilDate.getMinutes()+" " + utilDate.getSeconds()+"\n");
		
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx,
                IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		IPacket pkt = eth.getPayload();
		if(eth.isBroadcast() || eth.isMulticast())
		{
			return Command.CONTINUE;
		}
		else if (pkt instanceof IPv4) {
			calendar = GregorianCalendar.getInstance(); // creates a new calendar instance
			System.out.printf("Paket_in_num: %d \n",pi_count);
			System.out.printf("Now time is: "+calendar.get(Calendar.MINUTE)+" " + calendar.get(Calendar.SECOND)+" "+calendar.get(Calendar.MILLISECOND)+ "\n");
			
	        IPv4 ip_pkt = (IPv4) pkt;
	        int destIpAddress = ip_pkt.getDestinationAddress();
	        int srcIpAddress = ip_pkt.getSourceAddress();
	        //System.out.printf("\nversion : %d iden : %d pro : %d sour : %d\n",ip_pkt.getVersion(),ip_pkt.getIdentification(),
	        //		ip_pkt.getProtocol(),ip_pkt.getSourceAddress());
	        if (ip_pkt.getSourceAddress() != source_address)
	        {
	        		source_address=ip_pkt.getSourceAddress();
	        		pushBidirectionalVipRoutes(sw, pi, cntx,srcIpAddress,destIpAddress,false);
	        }
	        else
	        {
	        		source_address = 0;
	        		pushBidirectionalVipRoutes(sw, pi, cntx,srcIpAddress,destIpAddress,true);
	        }	        
	        //pushBidirectionalVipRoutes(sw, pi, cntx, destIpAddress, srcIpAddress);
	        //pushBidirectionalVipRoutes(sw, pi, cntx,srcIpAddress,destIpAddress);
	        
	        // packet out based on table rule
	        pushPacket(pkt, sw, pi.getBufferId(), pi.getInPort(), OFPort.OFPP_TABLE.getValue(),
                    cntx, true);
	        //System.out.printf("Now time is: "+utilDate.getMinutes()+" " + utilDate.getSeconds()+"\n");
	        calendar = GregorianCalendar.getInstance(); // creates a new calendar instance
	        System.out.printf("Now time is: "+calendar.get(Calendar.MINUTE)+" " + calendar.get(Calendar.SECOND)+" "+calendar.get(Calendar.MILLISECOND)+ "\n");
	        pi_count++;
	        return Command.STOP;
		}

		return Command.CONTINUE;
	}
	
	protected void pushBidirectionalVipRoutes(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx, int srcIpAddress, int destIpAddress,
			boolean forward) 
	{
		Collection<? extends IDevice> allDevices = deviceManager
                .getAllDevices();
		IDevice srcDevice = null;
        IDevice dstDevice = null;
        for (IDevice d : allDevices) {
            for (int j = 0; j < d.getIPv4Addresses().length; j++) {
                    if (srcDevice == null && srcIpAddress == d.getIPv4Addresses()[j])
                        srcDevice = d;
                    if (dstDevice == null && destIpAddress == d.getIPv4Addresses()[j]) {
                        dstDevice = d;
                    }
                    if (srcDevice != null && dstDevice != null)
                        break;
            }
        }  
        
     // srcDevice and/or dstDevice is null, no route can be pushed
        if (srcDevice == null || dstDevice == null) return;
        
        Long srcIsland = topology.getL2DomainId(sw.getId());

        if (srcIsland == null) {
            /*log.debug("No openflow island found for source {}/{}", 
                      sw.getStringId(), pi.getInPort());
                      */
            return;
        }
        
        // Validate that we have a destination known on the same island
        // Validate that the source and destination are not on the same switchport
        boolean on_same_island = false;
        boolean on_same_if = false;
        for (SwitchPort dstDap : dstDevice.getAttachmentPoints()) {
            long dstSwDpid = dstDap.getSwitchDPID();
            Long dstIsland = topology.getL2DomainId(dstSwDpid);
            if ((dstIsland != null) && dstIsland.equals(srcIsland)) {
                on_same_island = true;
                if ((sw.getId() == dstSwDpid) &&
                        (pi.getInPort() == dstDap.getPort())) {
                    on_same_if = true;
                }
                break;
            }
        }
        if (!on_same_island) {
            // Flood since we don't know the dst device
            
            return;
        }            
        
        if (on_same_if) {
            
            return;
        }
        
     // Install all the routes where both src and dst have attachment
        // points.  Since the lists are stored in sorted order we can 
        // traverse the attachment points in O(m+n) time
        SwitchPort[] srcDaps = srcDevice.getAttachmentPoints();
        Arrays.sort(srcDaps, clusterIdComparator);
        SwitchPort[] dstDaps = dstDevice.getAttachmentPoints();
        Arrays.sort(dstDaps, clusterIdComparator);
        
        int iSrcDaps = 0, iDstDaps = 0;

        // following Forwarding's same routing routine, retrieve both in-bound and out-bound routes for
        // all clusters.
        while ((iSrcDaps < srcDaps.length) && (iDstDaps < dstDaps.length)) {
            SwitchPort srcDap = srcDaps[iSrcDaps];
            SwitchPort dstDap = dstDaps[iDstDaps];
            Long srcCluster = 
                    topology.getL2DomainId(srcDap.getSwitchDPID());
            Long dstCluster = 
                    topology.getL2DomainId(dstDap.getSwitchDPID());

            int srcVsDest = srcCluster.compareTo(dstCluster);
            if (srcVsDest == 0) {
                if (!srcDap.equals(dstDap) && 
                        (srcCluster != null) && 
                        (dstCluster != null)) {
                	//System.out.printf("\n %s to %s",srcDap.getSwitchDPID(),dstDap.getSwitchDPID());
                	Route routeIn = null;
                	Route routeOut = null;
                	if (forward == true)
                	{
                		routeIn = 
                				routingEngine.getRoute(srcDap.getSwitchDPID(),
                                                   (short)srcDap.getPort(),
                                                   dstDap.getSwitchDPID(),
                                                   (short)dstDap.getPort(), 0);
                		//routingEngine.updateWeight_fromcounter(routeIn,10);
                		
                		routeOut = 
                				routingEngine.getRoute_back(dstDap.getSwitchDPID(),
                                                   (short)dstDap.getPort(),
                                                   srcDap.getSwitchDPID(),
                                                   (short)srcDap.getPort(), 0);
                	}
                	else if (forward == false)
                	{
                		routeIn = 
                                routingEngine.getRoute_back(srcDap.getSwitchDPID(),
                                                       (short)srcDap.getPort(),
                                                       dstDap.getSwitchDPID(),
                                                       (short)dstDap.getPort(), 0);
                		//routingEngine.updateWeight_fromcounter(routeIn,10);
                        routeOut = 
                                routingEngine.getRoute(dstDap.getSwitchDPID(),
                                                       (short)dstDap.getPort(),
                                                       srcDap.getSwitchDPID(),
                                                       (short)srcDap.getPort(), 0);
                        //routingEngine.updateWeight_fromcounter(routeOut,10);
                	}
                	//routingEngine.updateWeight_fromcounter(routeIn,10);
                    // use static flow entry pusher to push flow mod along in and out path
                    // in: match src client (ip, port), rewrite dest from vip ip/port to member ip/port, forward
                    // out: match dest client (ip, port), rewrite src from member ip/port to vip ip/port, forward
                    
                    if (routeIn != null) {
                    	 
                    	//routingEngine.updateWeight_fromcounter(routeIn,10);
                        pushStaticVipRoute(true, routeIn, srcIpAddress, destIpAddress,cntx);
                    }
                    
                    if (routeOut != null) {
                        pushStaticVipRoute(false, routeOut, srcIpAddress, destIpAddress,cntx);
                    }
                }
                iSrcDaps++;
                iDstDaps++;
            } else if (srcVsDest < 0) {
                iSrcDaps++;
            } else {
                iDstDaps++;
            }
        }
        return;
	}
    /**
     * used to push given route using static flow entry pusher
     * @param boolean inBound
     * @param Route route
     * @param IPClient client
     * @param LBMember member
     * @param long pinSwitch
     */
    public void pushStaticVipRoute(boolean inBound, Route route, int ipFrom, int ipTo,FloodlightContext cntx) {
        List<NodePortTuple> path = route.getPath();
        if (path.size()>0) {
		   //Wildcardc
		   Integer wildcard_hints = null;
		   OFFlowMod fm = (OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
		   //OFMatch
		   OFMatch match = new OFMatch();
		   match.setDataLayerType((short)0x800);
		   match.setNetworkDestination(ipTo);
		   match.setNetworkSource(ipFrom);
		   //OFActions
		   OFActionOutput action = new OFActionOutput();
		   action.setMaxLength((short)0xffff);
		   List<OFAction> actions = new ArrayList<OFAction>();
		   actions.add(action);

		   fm.setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)
			   .setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT)
			   .setBufferId(OFPacketOut.BUFFER_ID_NONE)
			   .setCookie((long)flowcookie_counter)
			   .setPriority((short)1024)
			   .setCommand(OFFlowMod.OFPFC_ADD)
			   .setMatch(match)
			   .setActions(actions)
			   .setLengthU(OFFlowMod.MINIMUM_LENGTH+OFActionOutput.MINIMUM_LENGTH);
					   	 
           for (int i = path.size()-1 ; i >0 ; i -=2 ) {
               
               long sw = path.get(i).getNodeId();
			   IOFSwitch swnode = floodlightProvider.getSwitch(sw);
			   wildcard_hints = ((Integer) swnode.getAttribute(IOFSwitch.PROP_FASTWILDCARDS)).intValue()
				   			& ~OFMatch.OFPFW_IN_PORT
							& ~OFMatch.OFPFW_NW_SRC_MASK
							& ~OFMatch.OFPFW_NW_DST_MASK
							& ~OFMatch.OFPFW_DL_TYPE;

               fm.setMatch(wildcard(match,swnode,wildcard_hints));
			   fm.getMatch().setInputPort(path.get(i-1).getPortId());
			   ((OFActionOutput)fm.getActions().get(0)).setPort(path.get(i).getPortId());
				
               try {
			   	   messageDamper.write(swnode,fm,cntx);
               } catch (IOException e) {
				   log.info("match string = {}",fm.getMatch().toString());
				   log.info("action string = {}",((OFActionOutput)fm.getActions().get(0)).toString());
               }
			   try {
				   fm = fm.clone();
			   }catch (CloneNotSupportedException e){
					log.error(" clone faile");
				}
					
           }
           flow_route.put(flowcookie_counter, route);
           flow_dead.put(flowcookie_counter, (long) 0);
           flowcookie_counter++;
           //System.out.printf("\n adding OFMatch " + match.toString());  
        }
        return;
    }
    
    
	protected OFMatch wildcard(OFMatch match, IOFSwitch sw,
                               Integer wildcard_hints) {
        if (wildcard_hints != null) {
            return match.clone().setWildcards(wildcard_hints.intValue());
        }
        return match.clone();
    }

    
    /**
     * used to push any packet - borrowed routine from Forwarding
     * 
     * @param OFPacketIn pi
     * @param IOFSwitch sw
     * @param int bufferId
     * @param short inPort
     * @param short outPort
     * @param FloodlightContext cntx
     * @param boolean flush
     */    
    public void pushPacket(IPacket packet, 
                           IOFSwitch sw,
                           int bufferId,
                           short inPort,
                           short outPort, 
                           FloodlightContext cntx,
                           boolean flush) {
        if (log.isTraceEnabled()) {
            log.trace("PacketOut srcSwitch={} inPort={} outPort={}", 
                      new Object[] {sw, inPort, outPort});
        }

        OFPacketOut po =
                (OFPacketOut) floodlightProvider.getOFMessageFactory()
                                                .getMessage(OFType.PACKET_OUT);

        // set actions
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(outPort, (short) 0xffff));

        po.setActions(actions)
          .setActionsLength((short) OFActionOutput.MINIMUM_LENGTH);
        short poLength =
                (short) (po.getActionsLength() + OFPacketOut.MINIMUM_LENGTH);

        // set buffer_id, in_port
        po.setBufferId(bufferId);
        po.setInPort(inPort);

        // set data - only if buffer_id == -1
        if (po.getBufferId() == OFPacketOut.BUFFER_ID_NONE) {
            if (packet == null) {
                log.error("BufferId is not set and packet data is null. " +
                          "Cannot send packetOut. " +
                        "srcSwitch={} inPort={} outPort={}",
                        new Object[] {sw, inPort, outPort});
                return;
            }
            byte[] packetData = packet.serialize();
            poLength += packetData.length;
            po.setPacketData(packetData);
        }

        po.setLength(poLength);

        try {
            counterStore.updatePktOutFMCounterStoreLocal(sw, po);
            messageDamper.write(sw, po, cntx, flush);
        } catch (IOException e) {
            log.error("Failure writing packet out", e);
        }
    }


protected class MyTimer  implements Runnable {

	

	
	//AllSwitchStatisticsResource myswitchresource;
	HashMap<String, Object> model = new HashMap<String, Object>();

	
    private OFStatisticsType statType;

    
 //   ArrayList<SwitchUtility> myswitchlist;
	
	
    public MyTimer() {
        this.statType = OFStatisticsType.FLOW;
        //this.statType = OFStatisticsType.PORT;

  //      myswitchlist = new ArrayList();
    }
    

	
	
	@Override
	public void run() {
		
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		
		//initail_switchinfo();			
		switchinfornupdating();

				
	}
		
	
    private void initail_switchinfo(){
    	
		Set<Long> switchDpids = floodlightProvider.getAllSwitchDpids();
		logger.info(floodlightProvider.getAllSwitchDpids().toString());
		
		int switchnum = 0;
		
	
        for (Long l  : switchDpids) {
        	
        	//SwitchUtility tmpswitchin;
        	//tmpswitchin = new SwitchUtility();
        	//myswitchlist.add(tmpswitchin);
        	switchnum++;
        
   	 		IOFSwitch sw = floodlightProvider.getSwitch(l);
   	 		logger.info(sw.toString());
   	 	
   	 		OFStatisticsRequest req = new OFStatisticsRequest();
   	 		req.setStatisticType(statType);
   	 		int requestLength = req.getLengthU();
        
   	 		Future <List<OFStatistics>> future = null;
   	 		List<OFStatistics> values = null;
        
   	 		/*OFPortStatisticsRequest specificReq = new OFPortStatisticsRequest();
   	 		specificReq.setPortNumber(OFPort.OFPP_NONE.getValue());
   	 		req.setStatistics(Collections.singletonList((OFStatistics)specificReq));
   	 		requestLength += specificReq.getLength();*/
   	 		
   	 	OFFlowStatisticsRequest specificReq = new OFFlowStatisticsRequest();
        OFMatch match = new OFMatch();
        match.setWildcards(0xffffffff);
        specificReq.setMatch(match);
        specificReq.setOutPort(OFPort.OFPP_NONE.getValue());
        specificReq.setTableId((byte) 0xff);
        req.setStatistics(Collections.singletonList((OFStatistics)specificReq));
        requestLength += specificReq.getLength();
   	 	   
   	 		req.setLengthU(requestLength);
   	 		try {
   	 			future = sw.queryStatistics(req);
   	 			values = future.get(10, TimeUnit.SECONDS);
   	 		} catch (Exception e) {
   	 			logger.error("Failure retrieving statistics from switch " + sw, e);
   	 		}
        

   	 		Iterator<OFStatistics> liIt = values.iterator();

   	 		
   	 		
   	 		while(liIt.hasNext()){
   	 			
   	 	//		Dropdata tmpdrop ;
   	 			
         //   	tmpdrop = new Dropdata();	            	
         //   	tmpswitchin.portdatalist.add(tmpdrop);
   	 			       	 			
            	/*OFPortStatisticsReply myreply = (OFPortStatisticsReply) liIt.next();
            	ByteBuffer bb = MappedByteBuffer.allocate(3);
                bb.putShort(0, myreply.getPortNumber()) ; */
   	 		OFFlowStatisticsReply myreply = (OFFlowStatisticsReply) liIt.next();
   	 	    ByteBuffer bb = MappedByteBuffer.allocate(3);
            bb.putShort(0, myreply.getTableId()) ;
                
                
             //   tmpdrop.getarround(myreply.getTransmitDropped());
             //   tmpdrop.getarroundrecv(myreply.getreceivePackets());
                //System.out.printf("Tableid : " +Unsigned.getUnsignedShort(bb, 0) + ", PacketNum : "+ myreply.getPacketCount() + "\n" );
            //	logger.info("Portnum : " +Unsigned.getUnsignedShort(bb, 0) + ", DropNum in 2 secs : "+tmpdrop.getdrops()  );
            //	logger.info("Portnum : " +Unsigned.getUnsignedShort(bb, 0) + ", Packetreceive in 2 secs : "+tmpdrop.getrecv() + "\n" );
   	 		}
        
        }
    }
    
    private void switchinfornupdating(){
    	while(true){

			try {
				Thread.sleep(5000);
			} catch (InterruptedException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			
			logger.info("Yeah now is 5 scenod past \n\n");
			
			
			Set<Long> switchDpids = floodlightProvider.getAllSwitchDpids();
			//logger.info(floodlightProvider.getAllSwitchDpids().toString());
			
			int switchnum = switchDpids.size();
			
		
	        for (Long l  : switchDpids) {
	        //	SwitchUtility tmpswitchin;
	        	

	        	
	        //	tmpswitchin = myswitchlist.get(switchnum);
	        		
	        	
	        	 
	        	//switchnum++;
	        	 IOFSwitch sw = floodlightProvider.getSwitch(l);
	        	logger.info(sw.toString());
	        	
	            OFStatisticsRequest req = new OFStatisticsRequest();
	            req.setStatisticType(statType);
	            int requestLength = req.getLengthU();
	            
	            Future <List<OFStatistics>> future = null;
	            List<OFStatistics> values = null;
	            
                /*OFPortStatisticsRequest specificReq = new OFPortStatisticsRequest();
                specificReq.setPortNumber(OFPort.OFPP_NONE.getValue());
                req.setStatistics(Collections.singletonList((OFStatistics)specificReq));
                requestLength += specificReq.getLength();*/
                
	            OFFlowStatisticsRequest specificReq = new OFFlowStatisticsRequest();
	            OFMatch match = new OFMatch();
	            match.setWildcards(0xffffffff);
	            specificReq.setMatch(match);
	            specificReq.setOutPort(OFPort.OFPP_NONE.getValue());
	            specificReq.setTableId((byte) 0xff);
	            req.setStatistics(Collections.singletonList((OFStatistics)specificReq));
	            requestLength += specificReq.getLength();
	            
                req.setLengthU(requestLength);
                try {
                    future = sw.queryStatistics(req);
                    values = future.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                	logger.error("Failure retrieving statistics from switch " + sw, e);
                }
                

	            Iterator<OFStatistics> liIt = values.iterator();
	    //        Iterator<SwitchUtility> sliIt = myswitchlist.iterator();
	            int portnum = 0;
	            
		        while(liIt.hasNext()){
		            	
		    //        	Dropdata tmpdrop ;
     	          	
		    //        	tmpdrop = tmpswitchin.portdatalist.get(portnum);
		            		      	
		            	/*OFPortStatisticsReply myreply = (OFPortStatisticsReply) liIt.next();
		            	ByteBuffer bb = MappedByteBuffer.allocate(3);
		                bb.putShort(0, myreply.getPortNumber()) ; */
		        	
		        	OFFlowStatisticsReply myreply = (OFFlowStatisticsReply) liIt.next();
		   	 	    ByteBuffer bb = MappedByteBuffer.allocate(3);
		            bb.putShort(0, myreply.getTableId()) ;
		            OFMatch matching = myreply.getMatch();
		                
		    //            tmpdrop.getarround(myreply.getTransmitDropped());
		    //            tmpdrop.getarroundrecv(myreply.getreceivePackets());
		        
		            System.out.printf(myreply.getCookie()  + ", PacketNum : "+ myreply.getPacketCount() + "\n" );        
		      //          System.out.printf("Portnum : " +Unsigned.getUnsignedShort(bb, 0) + ", PacketNum : "+ myreply.getreceivePackets() + "\n" );
		     //       	logger.info("Portnum : " +Unsigned.getUnsignedShort(bb, 0) + ", DropNum in 2 secs : "+tmpdrop.getdrops()  );
		     //       	logger.info("Portnum : " +Unsigned.getUnsignedShort(bb, 0) + ", Packetreceive in 2 secs : "+tmpdrop.getrecv() + "\n" );

		            	portnum++;
		            	
		            	 Route for_update = flow_route.get(myreply.getCookie());
		            	 if ( for_update != null ){
                        //flow_dead.remove(myreply.getCookie());
		            	flow_dead.put(myreply.getCookie(),(long)0);
						routingEngine.updateWeight_fromcounter(for_update,(int) myreply.getPacketCount());
						
		            	 }
		            	
		        }

	        }
//
	        for (Object key : flow_dead.keySet())
	        {
	        	if(flow_dead.get(key)==(long)1)
	        	{
	        		Route for_delete = flow_route.get(key);	
	        		routingEngine.updateWeight_fromcounter(for_delete,(-1));
	        		//flow_route.remove(key);
	        		//flow_dead.remove(key);
	        		flow_dead.put((Long) key,(long)2);
	        		System.out.printf("\nflow delete : %s\n",key.toString());
	        	}
	        	else if(flow_dead.get(key)==(long)0)
	        	{
	        		//flow_dead.remove(key);
	            	flow_dead.put((Long) key,(long)1);
	        	}
	        	  
	        }	        
		}
    	
    }


	
}

				
}

