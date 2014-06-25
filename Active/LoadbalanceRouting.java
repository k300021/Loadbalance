package net.floodlightcontroller.LoadBalancing.Active;

import java.util.*;

import org.openflow.protocol.OFPacketOut;

import net.floodlightcontroller.LoadBalancing.Active.ActiveLoadBalancer.flow;
import net.floodlightcontroller.LoadBalancing.Active.dijkstra.engine.DijkstraAlgorithm;
import net.floodlightcontroller.LoadBalancing.Active.dijkstra.model.Edge;
import net.floodlightcontroller.LoadBalancing.Active.dijkstra.model.Graph;
import net.floodlightcontroller.LoadBalancing.Active.dijkstra.model.Vertex;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.linkdiscovery.LinkInfo;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.topology.NodePortTuple;

import java.util.concurrent.Semaphore;

public class LoadbalanceRouting implements ILinkDiscoveryListener,
        ILoadbalanceRoutingService, IFloodlightModule {
    class pairNodePortTuple extends LinkedList<NodePortTuple>{
        pairNodePortTuple(NodePortTuple first, NodePortTuple second) {
            this.add(first);
            this.add(second);
            
        }
    }

    class pairSwitch extends LinkedList<Long> {
        pairSwitch(long first, long second) {
            this.add(first);
            this.add(second);
        }
    }

    private final int DEFAULT_LINK_WEIGHT = 1;
    protected ILinkDiscoveryService linkDiscovery;
    protected IRoutingService routingEngine;
    protected HashMap<pairNodePortTuple, Set<Route>> pathMap = new HashMap<pairNodePortTuple, Set<Route> >();
    protected HashMap<pairSwitch, List<Edge>> switchLink = new HashMap<pairSwitch, List<Edge> >();
    protected HashMap<Short, Route> port_route = new HashMap<Short, Route>();
    protected HashMap<Route, Route> route_match = new HashMap<Route, Route>();
    private List<Edge> links = new ArrayList<Edge>();
    private List<Vertex> switchs = new ArrayList<Vertex>();
    final Semaphore mutex = new Semaphore(1);
    
protected HashMap<pairNodePortTuple, flow> PathCache = new HashMap<pairNodePortTuple, flow>();
	
	protected class  flow{
	       public Route route;
	       public boolean used;   
	       }

    class path extends LinkedList<Vertex> {
        path(LinkedList<Vertex> a) {
            super(a);
        }
    }

    @Override
    public Route getRoute(long srcId, short srcPort, long dstId, short dstPort,
            long cookie) {
        // TODO This method will return a load balance routing path
        // If the pair<NodePortTuple> -> set<Route> is empty
        // call findFirstPATH to fast find the first path, and call
        // findOtherPATH in other thread.
        // If pair<NodePortTuple> -> set<Route> is not empty,
        // choose a path from set<Route> by round robin maybe?
        pairNodePortTuple query = new pairNodePortTuple(new NodePortTuple(
                srcId, srcPort), new NodePortTuple(dstId, dstPort));
        Set<Route> retRoute = pathMap.get(query);
        if (retRoute == null || retRoute.size() == 0) {
            //return findFirstPATH(srcId, srcPort, dstId, dstPort);
            Route ret = findOtherPATH(srcId, srcPort, dstId, dstPort, 1); 
            flow temp = new flow();
            temp.route=ret;
            temp.used=true;
            PathCache.put(query,temp);
            return ret;
        } else {

        }
        return null;
    }

    public Route getRoute_back(long srcId, short srcPort, long dstId, short dstPort,
            long cookie) {
        pairNodePortTuple query = new pairNodePortTuple(new NodePortTuple(
                srcId, srcPort), new NodePortTuple(dstId, dstPort));
        Set<Route> retRoute = pathMap.get(query);
        if (retRoute == null || retRoute.size() == 0) {
            //return findFirstPATH(srcId, srcPort, dstId, dstPort);
            Route ret = findOtherPATH_back(srcId, srcPort, dstId, dstPort, 1); 
            return ret;
        } else {

        }
        return null;
    }
    
    @Override
    public void linkDiscoveryUpdate(LDUpdate update) {
        // TODO this function will update the switch_connect_map
        //System.out.printf("update link = %s\n", update.toString());
    }

    @Override
    public void linkDiscoveryUpdate(List<LDUpdate> updateList) {
        // TODO this function will update the switch_connect_map
    	//int i=0;
        for (LDUpdate currentUpdate : updateList) {
             //System.out.printf("\nupdate list[%d] = %s\n", i,currentUpdate.toString());
             //i++;
            switch (currentUpdate.getOperation()) {
            case LINK_UPDATED: {
                Vertex from = null;
                Vertex to = null;
                for (Vertex currentSwitch : switchs) {
                    if (from == null
                            && currentSwitch.getId() == currentUpdate.getSrc()) {
                        from = new Vertex(currentSwitch);
                        from.setSwitchPort(new NodePortTuple(currentUpdate
                                .getSrc(), currentUpdate.getSrcPort()));
                        continue;
                    } else if (to == null
                            && currentSwitch.getId() == currentUpdate.getDst()) {
                        to = new Vertex(currentSwitch);
                        to.setSwitchPort(new NodePortTuple(currentUpdate
                                .getDst(), currentUpdate.getDstPort()));
                        continue;
                    }
                    if (from != null && to != null)
                        break;
                }
                if (from != null && to != null) {
                    Edge newLink = new Edge(String.valueOf(currentUpdate
                            .getSrc())
                            + "-"
                            + String.valueOf(currentUpdate.getSrcPort())
                            + "to"
                            + String.valueOf(currentUpdate.getDst())
                            + "-"
                            + String.valueOf(currentUpdate.getDstPort()), from,
                            to, DEFAULT_LINK_WEIGHT);
                    links.add(newLink);
                    
                  //System.out.printf("\nupdate[%d] = %s\n", i,newLink.getId());
                  //i++;
                    
                    //victor 3/11 start
                    	/*Edge newLink_reverse = new Edge(String.valueOf(currentUpdate
                            .getDst())
                            + "-"
                            + String.valueOf(currentUpdate.getDstPort())
                            + "to"
                            + String.valueOf(currentUpdate.getSrc())
                            + "-"
                            + String.valueOf(currentUpdate.getSrcPort()), to,
                            from, DEFAULT_LINK_WEIGHT);
                    links.add(newLink_reverse);
                    List<Edge> switchPairLinks_reverse = switchLink.get(new pairSwitch(
                            currentUpdate.getDst(), currentUpdate.getSrc()));
                    if (switchPairLinks_reverse == null) {
                        List<Edge> t = new ArrayList<Edge>();
                        t.add(newLink_reverse);
                        switchLink.put(new pairSwitch(currentUpdate.getDst(),
                                currentUpdate.getSrc()), t);
                    }
                    else {
                        switchPairLinks_reverse.add(newLink_reverse);
                    }*/
                    //victor 3/11 end
                    List<Edge> switchPairLinks = switchLink.get(new pairSwitch(
                            currentUpdate.getSrc(), currentUpdate.getDst()));
                    if (switchPairLinks == null) {
                        List<Edge> l = new ArrayList<Edge>();
                        l.add(newLink);
                        switchLink.put(new pairSwitch(currentUpdate.getSrc(),
                                currentUpdate.getDst()), l);
                    }
                    else {
                        switchPairLinks.add(newLink);
                    }
                }

            }
                break;
            case LINK_REMOVED: {
                String targetId = String.valueOf(currentUpdate.getSrc()) + "-"
                        + String.valueOf(currentUpdate.getSrcPort()) + "to"
                        + String.valueOf(currentUpdate.getDst()) + "-"
                        + String.valueOf(currentUpdate.getDstPort());
                for (Edge o : links) {
                    if (o.getId() == targetId)
                        links.remove(o);
                }
                List<Edge> switchPairLinks = switchLink.get(new pairSwitch(
                        currentUpdate.getSrc(), currentUpdate.getDst()));
                if (switchPairLinks != null) {
                    for(Edge l : switchPairLinks) {
                        if(l.getId() == targetId)
                            switchPairLinks.remove(l);
                    }
                }
                else {
                    
                }
                //victor 3/16 start
                /*
                String targetId_reverse = String.valueOf(currentUpdate.getDst()) + "-"
                        + String.valueOf(currentUpdate.getDstPort()) + "to"
                        + String.valueOf(currentUpdate.getSrc()) + "-"
                        + String.valueOf(currentUpdate.getSrcPort());
                for (Edge g : links) {
                    if (g.getId() == targetId_reverse)
                        links.remove(g);
                }
                List<Edge> switchPairLinks_reverse = switchLink.get(new pairSwitch(
                        currentUpdate.getDst(), currentUpdate.getSrc()));
                if (switchPairLinks_reverse != null) {
                    for(Edge l : switchPairLinks_reverse) {
                        if(l.getId() == targetId_reverse)
                        	switchPairLinks_reverse.remove(l);
                    }
                }
                */
                //victor 3/16 end
            }
                break;
            case SWITCH_UPDATED:
                switchs.add(new Vertex(currentUpdate.getSrc(), currentUpdate
                        .toString()));
                break;
            case SWITCH_REMOVED:
                switchs.remove(new Vertex(currentUpdate.getSrc(), currentUpdate
                        .toString()));
                break;
            default:
                continue;
            }
        }
    }

   /* public Route findFirstPATH(long srcId, short srcPort, long dstId,
            short dstPort) {
        // TODO this function will use the default routing module to find the
        // first path
        // and add this path to the pair<NodePortTuple> -> set<Route>
        return routingEngine.getRoute(srcId, srcPort, dstId, dstPort, 0);
    }*/

    public Route findOtherPATH(long srcId, short srcPort, long dstId,
            short dstPort, long count) {
        // TODO this function will find many path by some algorithm, and
        // add them into the set<Route>
        // This function should run in other thread
        // remember to use mutex
        if(srcId == dstId) {
            Route rt = new Route(srcId, dstId);
            List<NodePortTuple> switchPorts = new ArrayList<NodePortTuple>();
            switchPorts.add(new NodePortTuple(srcId, srcPort));
            switchPorts.add(new NodePortTuple(dstId, dstPort));
            rt.setPath(switchPorts);
            return rt;
        }
            
        Graph graph = new Graph(switchs, links);
        DijkstraAlgorithm dijkstra = new DijkstraAlgorithm(graph);
        Vertex from = null;
        Vertex to = null;

        for (Vertex v : switchs) {
            if (v.getId() == srcId)
                from = v;
            else if (v.getId() == dstId)
                to = v;

            if (from != null && to != null)
                break;
        }
        dijkstra.execute(from);
        LinkedList<Vertex> dijpath = dijkstra.getPath(to);
        if(dijpath == null) {
            return pathToRoute(null);
        }
        path p = new path(dijpath);
        Route rt = pathToRoute(p);
        //System.out.printf("execute updateWeight\n");
        try {
			updateWeight(rt, 1);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        port_route.put(srcPort, rt);
        List<NodePortTuple> rtPath = new ArrayList<NodePortTuple>();
        rtPath.add(new NodePortTuple(srcId, srcPort));
        rtPath.addAll(rt.getPath()); //getpath return switchports
        rtPath.add(new NodePortTuple(dstId, dstPort));
        rt.setPath(rtPath);
        return rt;
    }

    public Route findOtherPATH_back(long srcId, short srcPort, long dstId,
            short dstPort, long count) {
        // TODO this function will find many path by some algorithm, and
        // add them into the set<Route>
        // This function should run in other thread
        // remember to use mutex
    	//System.out.printf("execute findOtherPATH_back\n");
        if(srcId == dstId) {
            Route rt = new Route(srcId, dstId);
            List<NodePortTuple> switchPorts = new ArrayList<NodePortTuple>();
            switchPorts.add(new NodePortTuple(srcId, srcPort));
            switchPorts.add(new NodePortTuple(dstId, dstPort));
            rt.setPath(switchPorts);
            return rt;
        }
            
        Graph graph = new Graph(switchs, links);
        DijkstraAlgorithm dijkstra = new DijkstraAlgorithm(graph);
        Vertex from = null;
        Vertex to = null;

        for (Vertex v : switchs) {
            if (v.getId() == srcId)
                from = v;
            else if (v.getId() == dstId)
                to = v;

            if (from != null && to != null)
                break;
        }
        dijkstra.execute(from);
        LinkedList<Vertex> dijpath = dijkstra.getPath(to);
        if(dijpath == null) {
            return pathToRoute(null);
        }
        path p = new path(dijpath);
        Route rt = pathToRoute(p);
        
        try {
			updateWeight(rt, 0);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        port_route.put(srcPort, rt);
        List<NodePortTuple> rtPath = new ArrayList<NodePortTuple>();
        rtPath.add(new NodePortTuple(srcId, srcPort));
        rtPath.addAll(rt.getPath()); //getpath return switchports
        rtPath.add(new NodePortTuple(dstId, dstPort));
        rt.setPath(rtPath);
        return rt;
    }

    
   /* private Route getRouteFromSet(Set<Route> routes) {
        Collections.sort(links, new Comparator<Edge>() {
            public int compare(Edge o1, Edge o2) {
                return o2.getWeight() - o1.getWeight();
            }
        });

        return null;
    }*/
    public void updateWeight_fromcounter(Route route, int value) {
    	try {
			updateWeight_fromcounter_call(route,  value);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }
    public void updateWeight_fromcounter_call(Route route, int value)throws InterruptedException {
    	mutex.acquire();
    	try {
        List<NodePortTuple> rpath = route.getPath(); //getpath return switchports
        if (rpath != null){
        //System.out.printf("\nrpath not null\n");	
        for (int i = 1; i < (rpath.size()-1); i += 2) {
            NodePortTuple lineFrom = rpath.get(i);
            //System.out.printf("\nlinfrom ->"+ lineFrom.toString()+"\n");
            NodePortTuple lineTo = rpath.get(i + 1);
            //System.out.printf("\nlinto ->"+ lineTo.toString()+"\n");
            List<Edge> pairSwitchLinks = switchLink.get(new pairSwitch(lineFrom.getNodeId(), lineTo.getNodeId()));
            if (pairSwitchLinks != null)
            for (Edge line : pairSwitchLinks) {
                if (line.getSource().getSwitchPort() == lineFrom
                        && line.getDestination().getSwitchPort() == lineTo) {
                    line.setWeight(1 + value);
            //System.out.printf("\nvalue = %d, updateweight[%d] = %s-%s to %s-%s\n",value, i,line.getSource(),line.getSource().getSwitchPort(),
           //line.getDestination(),line.getDestination().getSwitchPort());
                    break;
                }

            }
            
        }}
    	}
    	finally {  
            // 退出核心区  
            mutex.release();  
            // 增加非空信号量，允许获取商品
    	}
    	return;
    }
    

    public void updateWeight(Route route, int value)throws InterruptedException {
        // TODO this function will update the link weight in
        // switch_connect_map(if any)
    	//System.out.printf("I love Sandy\n");
    	mutex.acquire();
    	try {
        List<NodePortTuple> rpath = route.getPath(); //getpath return switchports
        if (rpath != null){
        //System.out.printf("\nrpath not null\n");	
        for (int i = 0; i < rpath.size(); i += 2) {
            NodePortTuple lineFrom = rpath.get(i);
            //System.out.printf("\nlinfrom ->"+ lineFrom.toString()+"\n");
            NodePortTuple lineTo = rpath.get(i + 1);
            //System.out.printf("\nlinto ->"+ lineTo.toString()+"\n");
            List<Edge> pairSwitchLinks = switchLink.get(new pairSwitch(lineFrom.getNodeId(), lineTo.getNodeId()));
            if (pairSwitchLinks != null)
            for (Edge line : pairSwitchLinks) {
                if (line.getSource().getSwitchPort() == lineFrom
                        && line.getDestination().getSwitchPort() == lineTo) {
                    line.setWeight(line.getWeight() + value);
            System.out.printf("\nvalue = %d, updateweight[%d] = %s-%s to %s-%s\n",value, i,line.getSource(),line.getSource().getSwitchPort(),
           line.getDestination(),line.getDestination().getSwitchPort());
                    break;
                }

            }
            
        }}
    	}
    	finally {  
            // 退出核心区  
            mutex.release();  
            // 增加非空信号量，允许获取商品
    	}
    	return;
    }

    private Route pathToRoute(path p) {  //make go through port to a list
        if (p == null)
            return null;

        Route rt = new Route(p.getFirst().getId(), p.getLast().getId());
        List<NodePortTuple> switchPorts = new ArrayList<NodePortTuple>();
        long switchFrom = p.get(0).getId();
        for (int i = 1; i < p.size(); ++i) {
            long switchTo = p.get(i).getId();
            List<Edge> pairSwitchLinks = switchLink.get(new pairSwitch(
                    switchFrom, switchTo));
            if (pairSwitchLinks == null) {
                return null;
            }
            Edge minEdge = null;
            int minWeight = Integer.MAX_VALUE;
            for (Edge line : pairSwitchLinks) {
                if (line.getWeight() < minWeight) {
                    minEdge = line;
                }
            }
            switchPorts.add(minEdge.getSource().getSwitchPort());
            switchPorts.add(minEdge.getDestination().getSwitchPort());
            switchFrom = switchTo;
        }
        rt.setPath(switchPorts);
        return rt;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
        // l.add(ITopologyService.class);
        l.add(ILoadbalanceRoutingService.class);
        return l;
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        Map<Class<? extends IFloodlightService>, IFloodlightService> m = new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
        // We are the class that implements the service
        m.put(ILoadbalanceRoutingService.class, this);
        return m;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
        l.add(IFloodlightProviderService.class);
        l.add(ITopologyService.class);
        l.add(ILinkDiscoveryService.class);
        return l;
    }

    @Override
    public void init(FloodlightModuleContext context)
            throws FloodlightModuleException {
        // TODO Auto-generated method stub
        linkDiscovery = context.getServiceImpl(ILinkDiscoveryService.class);
        routingEngine = context.getServiceImpl(IRoutingService.class);
    }

    @Override
    public void startUp(FloodlightModuleContext context)
            throws FloodlightModuleException {
        // TODO Auto-generated method stub
        linkDiscovery.addListener(this);
    }
}
