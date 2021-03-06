package trader.service.tradlet;

import java.util.*;
import java.util.concurrent.ExecutorService;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import trader.common.beans.BeansContainer;
import trader.common.config.ConfigUtil;
import trader.common.exception.AppException;
import trader.common.exchangeable.Exchangeable;
import trader.common.util.ConversionUtil;
import trader.service.beans.DiscoverableRegistry;
import trader.service.md.MarketData;
import trader.service.md.MarketDataService;
import trader.service.plugin.Plugin;
import trader.service.plugin.PluginListener;
import trader.service.plugin.PluginService;
import trader.service.ta.LeveledTimeSeries;
import trader.service.ta.TAService;
import trader.service.tradlet.TradletGroup.State;

/**
 * 交易策略(Tradlet)/策略组(TradletGroup)的管理和事件分发
 */
@Service
public class TradletServiceImpl implements TradletService, PluginListener
{
    private static final Logger logger = LoggerFactory.getLogger(TradletServiceImpl.class);

    static final String ITEM_SUFFIX_DISRUPTOR_WAIT_STRATEGY = "/disruptor/waitStrategy";
    static final String ITEM_SUFFIX_DISRUPTOR_RINGBUFFER_SIZE = "/disruptor/ringBufferSize";

    static final String ITEM_GLOBAL_DISRUPTOR_WAIT_STRATEGY = "/TradletService"+ITEM_SUFFIX_DISRUPTOR_WAIT_STRATEGY;
    static final String ITEM_GLOBAL_DISRUPTOR_RINGBUFFER_SIZE = "/TradletService"+ITEM_SUFFIX_DISRUPTOR_RINGBUFFER_SIZE;

    static final String ITEM_TRADLETGROUP = "/TradletService/tradletGroup";

    static final String ITEM_TRADLETGROUPS = ITEM_TRADLETGROUP+"[]";

    @Autowired
    private BeansContainer beansContainer;

    @Autowired
    private MarketDataService mdService;

    @Autowired
    private TAService taService;

    @Autowired
    private PluginService pluginService;

    @Autowired
    private ExecutorService executorService;

    private Map<String, TradletInfo> tradletInfos = new HashMap<>();

    private ArrayList<TradletGroupEngine> groupEngines = new ArrayList<>();

    @Override
    public void init(BeansContainer beansContainer) throws AppException
    {
        mdService.addListener((MarketData md)->{
            queueGroupMDEvent(md);
        });
        taService.addListener((Exchangeable e, LeveledTimeSeries series)->{
            queueBarEvent(e, series);
        });
        pluginService.registerListener(this);
        tradletInfos = loadStandardTradlets();
        reloadTradletInfos(filterTradletPlugins(pluginService.getAllPlugins()));
        reloadGroups();
    }

    @Override
    @PreDestroy
    public void destroy() {

    }

    @Override
    public Collection<TradletInfo> getTradletInfos() {
        return tradletInfos.values();
    }

    @Override
    public TradletInfo getTradletInfo(String tradletId) {
        return tradletInfos.get(tradletId);
    }

    @Override
    public Collection<TradletGroup> getGroups() {
        return (Collection)Collections.unmodifiableList(groupEngines);
    }

    @Override
    public TradletGroup getGroup(String groupId) {
        for(int i=0;i<groupEngines.size();i++) {
            if ( groupEngines.get(i).getGroup().getId().equals(groupId)) {
                return groupEngines.get(i).getGroup();
            }
        }
        return null;
    }

    @Override
    public void onPluginChanged(List<Plugin> updatedPlugins) {
        //只关注包含有交易策略的类
        final List<Plugin> tacticPlugins = filterTradletPlugins(updatedPlugins);
        executorService.execute(()->{
            Set<String> updatedTradletIds = reloadTradletInfos(tacticPlugins);
            //重新加载受影响的TradletGroup
            queueGroupUpdatedevent(updatedTradletIds);
        });
    }

    /**
     * 返回所有含有交易策略实现接口Tradlet的插件
     */
    private List<Plugin> filterTradletPlugins(List<Plugin> plugins){
        final List<Plugin> tradletPlugins = new LinkedList<>();
        for(Plugin plugin:plugins) {
            if( plugin.getExposedInterfaces().contains(Tradlet.class.getName())) {
                tradletPlugins.add(plugin);
            }
        }
        return tradletPlugins;
    }

    /**
     * 尝试策略实现类
     */
    private Set<String> reloadTradletInfos(List<Plugin> updatedPlugins) {
        var allTradletInfos = new HashMap<>(tradletInfos);
        Set<String> updatedPluginIds = new TreeSet<>();
        Set<String> updatedTradletIds = new TreeSet<>();
        for(Plugin plugin:updatedPlugins) {
            updatedPluginIds.add(plugin.getId());
        }

        //从已有的策略中删除更新的Plugin
        for(Iterator<Map.Entry<String, TradletInfo>> it = allTradletInfos.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, TradletInfo> entry = it.next();
            Plugin tradletPlugin = entry.getValue().getPlugin();
            if ( tradletPlugin!=null && updatedPluginIds.contains(tradletPlugin.getId())) {
                it.remove();
            }
        }
        //从更新的Plugin发现Tradlet实现类
        long timestamp = System.currentTimeMillis();
        for(Plugin plugin:updatedPlugins) {
            Map<String, Class<Tradlet>> tradletClasses = plugin.getBeanClasses(Tradlet.class);
            for(String id:tradletClasses.keySet()) {
                Class<Tradlet> clazz = tradletClasses.get(id);
                updatedTradletIds.add(id);
                allTradletInfos.put(id, new TradletInfoImpl(id, clazz, plugin, timestamp));
            }
        }
        this.tradletInfos = allTradletInfos;
        String message = "Total tradlets "+allTradletInfos.size()+" loaded, "+updatedTradletIds+" updated from plugins: "+updatedPluginIds+" at timestamp "+timestamp;
        if ( updatedTradletIds.isEmpty() ) {
            logger.debug(message);
        }else {
            logger.info(message);
        }

        return updatedTradletIds;
    }

    /**
     * 加载标准策略实现类(不支持重新加载)
     */
    private Map<String, TradletInfo> loadStandardTradlets(){
        Map<String, Class<Tradlet>> tradletClasses = DiscoverableRegistry.getConcreteClasses(Tradlet.class);
        if ( tradletClasses==null ) {
            return Collections.emptyMap();
        }
        Map<String, TradletInfo> result = new HashMap<>();
        long timestamp = System.currentTimeMillis();
        for(String id:tradletClasses.keySet()) {
            result.put(id, new TradletInfoImpl(id, tradletClasses.get(id), null, timestamp));
        }
        return result;
    }

    /**
     * 重新加载交易策略组的配置.
     *
     * @return 返回新增或更新的GroupId
     */
    @Override
    public JsonObject reloadGroups() throws AppException
    {
        JsonArray newGroupIds = new JsonArray(), updatedGroupIds = new JsonArray(), deletedGroupIds = new JsonArray();
        Map<String, TradletGroupEngine> newGroupEngines = new TreeMap<>();
        //Key: groupId, Value groupConfig Text
        Map<String, TradletGroupTemplate> updatedGroupTemplates = new TreeMap<>();
        Map<String, TradletGroupEngine> currGroupEngines = new HashMap<>();
        for(TradletGroupEngine groupEngine:groupEngines) {
            currGroupEngines.put(groupEngine.getGroup().getId(), groupEngine);
        }
        Map<String, TradletGroupEngine> allGroupEngines = new HashMap<>();

        for(Map groupElem:(List<Map>)ConfigUtil.getObject(ITEM_TRADLETGROUPS)) {
            String groupId = ConversionUtil.toString(groupElem.get("id"));
            String groupConfig = ConversionUtil.toString( groupElem.get("text") );
            TradletGroupEngine groupEngine = currGroupEngines.remove(groupId);
            if (groupEngine != null && groupEngine.getGroup().getConfig().equals(groupConfig)) {
                //没有变化, 忽略
            } else {
                if (groupEngine == null) { // 新增Group
                    groupEngine = new TradletGroupEngine(createGroup(groupId, groupConfig));
                    newGroupEngines.put(groupId, groupEngine);
                    newGroupIds.add(groupId);
                } else { //更新Group
                    updatedGroupTemplates.put(groupId, TradletGroupTemplate.parse(beansContainer, groupEngine.getGroup(), groupConfig));
                    updatedGroupIds.add(groupId);
                }
            }
            allGroupEngines.put(groupId, groupEngine);
        }

        //为更新的策略组发送更新Event
        for(String groupId:updatedGroupTemplates.keySet()) {
            TradletGroupEngine groupEngine = allGroupEngines.get(groupId);
            groupEngine.queueEvent(TradletEvent.EVENT_TYPE_MISC_GROUP_UPDATE, updatedGroupTemplates.get(groupId));
        }
        //currGroupEngine 如果还有值, 是内存中存在但是配置文件已经删除, 需要将状态置为Disabled
        for(TradletGroupEngine deletedGroupEngine: currGroupEngines.values()) {
            deletedGroupEngine.getGroup().setState(State.Disabled);
            deletedGroupEngine.destroy();
            deletedGroupIds.add(deletedGroupEngine.getGroup().getId());
        }
        //为新增策略组创建新的线程
        for(TradletGroupEngine engine:newGroupEngines.values()) {
            try{
                engine.init(beansContainer);
            }catch(Throwable t) {
                logger.error("Tradlet group "+engine.getGroup().getId()+" init failed", t);
            }
        }
        String message = "Reload "+allGroupEngines.size()+" tradlet groups: "+(allGroupEngines.keySet())+", add: "+newGroupEngines.keySet()+", updated: "+updatedGroupTemplates.keySet()+", removed: "+currGroupEngines.keySet();
        logger.info(message);
        groupEngines = new ArrayList<>(allGroupEngines.values());
        JsonObject result = new JsonObject();
        result.add("new", newGroupIds);
        result.add("updated", updatedGroupIds);
        result.add("deleted", deletedGroupIds);
        return result;
    }

    private TradletGroupImpl createGroup(String groupId, String groupConfig) throws AppException
    {
        TradletGroupImpl group = new TradletGroupImpl(beansContainer, groupId);
        group.update(TradletGroupTemplate.parse(beansContainer, group, groupConfig));
        return group;
    }

    /**
     * 当Tradlet有更新时, 通知受影响的TradletGroup重新加载
     */
    private void queueGroupUpdatedevent(Set<String> updatedTradletIds) {
        for(TradletGroupEngine groupEngine:groupEngines) {
            TradletGroupImpl group = groupEngine.getGroup();
            List<TradletHolder> tradletHolders = group.getTradletHolders();
            String tradletId = null;
            for(int i=0;i<tradletHolders.size();i++) {
                if ( updatedTradletIds.contains( tradletHolders.get(i).getId() ) ) {
                    tradletId = tradletHolders.get(i).getId();
                    break;
                }
            }
            if ( tradletId!=null ) {
                String groupConfig = ConfigUtil.getString(ITEM_TRADLETGROUP+"#"+group.getId()+".text");
                groupEngine.queueEvent(TradletEvent.EVENT_TYPE_MISC_GROUP_UPDATE, groupConfig);
                logger.info("策略组 "+group.getId()+" 重新加载, 因 tradlet 更新: "+tradletId);
            }
        }
    }

    /**
     * 派发行情事件到交易组
     */
    private void queueGroupMDEvent(MarketData md) {
        for(int i=0;i<groupEngines.size();i++) {
            TradletGroupEngine groupEngine = groupEngines.get(i);
            if ( groupEngine.getGroup().getExchangeable().equals(md.instrumentId) ) {
                groupEngine.queueEvent(TradletEvent.EVENT_TYPE_MD_TICK, md);
            }
        }
    }

    /**
     * 派发KBar事件到交易组
     */
    private void queueBarEvent(Exchangeable e, LeveledTimeSeries series) {
        for(int i=0;i<groupEngines.size();i++) {
            TradletGroupEngine groupEngine = groupEngines.get(i);
            if ( groupEngine.getGroup().getExchangeable().equals(e) ) {
                groupEngine.queueEvent(TradletEvent.EVENT_TYPE_MD_BAR, series);
            }
        }
    }

}
