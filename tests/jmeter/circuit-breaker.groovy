import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Cached, shared by native JMeter JSR223 elements. No external Groovy libraries/plugins. */
class FlashSaleCb {
    static final String KEY = 'flashsale.cb.state'
    static long now() { System.currentTimeMillis() }
    static void failure(Map s, String code) { s.failures.add([time:Instant.now().toString(), code:code]) }
    static void scrub(def result) {
        if (result == null) return
        result.setResponseData(new byte[0]); result.setSamplerData('')
        result.setRequestHeaders(''); result.setResponseHeaders('')
    }
    static Map request(Map s, String service, String path, String token, String method='GET', String body=null) {
        long begin=now(); HttpURLConnection c=null
        try {
            c=(HttpURLConnection)new URL("${s.config.protocol}://${s.config.host}:${s.config[service+'Port']}${path}").openConnection()
            c.connectTimeout=2000; c.readTimeout=5000; c.requestMethod=method
            c.setRequestProperty('Accept','application/json'); c.setRequestProperty('Connection','close')
            if (token) c.setRequestProperty('Authorization','Bearer '+token)
            if (body!=null) {
                c.doOutput=true; c.setRequestProperty('Content-Type','application/json; charset=UTF-8')
                c.outputStream.withCloseable { it.write(body.getBytes(StandardCharsets.UTF_8)) }
            }
            int status=c.responseCode
            def stream=status>=400 ? c.errorStream : c.inputStream
            String text=stream==null ? '' : stream.withCloseable { it.getText('UTF-8') }
            def parsed=null
            if (text) { try { parsed=new JsonSlurper().parseText(text) } catch (Exception ignored) {} }
            return [status:status,body:parsed,latencyMs:now()-begin]
        } catch (Exception problem) {
            return [status:0,body:null,latencyMs:now()-begin,transportError:problem.class.simpleName]
        } finally { if(c!=null)c.disconnect() }
    }
    static long eventTime(Object text) {
        if (text==null)return -1
        try { return Instant.parse(text.toString()).toEpochMilli() } catch(Exception ignored){}
        try { return OffsetDateTime.parse(text.toString()).toInstant().toEpochMilli() } catch(Exception ignored){}
        try { return ZonedDateTime.parse(text.toString()).toInstant().toEpochMilli() } catch(Exception ignored){}
        return -1
    }
    static String fingerprint(Map e) { "${e.creationTime}|${e.type}|${e.stateTransition}|${e.circuitBreakerName}" }
    static List eventList(Map response) { response.status==200 && response.body instanceof Map ? (response.body.circuitBreakerEvents ?: []) as List : [] }
    static void observe(Map s) {
        try {
            def state=request(s,'order','/actuator/circuitbreakers',s.adminToken)
            if(state.status==200 && state.body?.circuitBreakers?.inventory instanceof Map) {
                Map b=state.body.circuitBreakers.inventory
                s.lastState=b.state.toString()
                Map safe=[observedAt:Instant.now().toString(),state:s.lastState]
                ['bufferedCalls','failedCalls','successfulCalls','notPermittedCalls','failureRate','slowCallRate','slowCalls'].each { if(b.containsKey(it))safe[it]=b[it] }
                s.observations.add(safe)
            } else s.observerErrors.incrementAndGet()
            def response=request(s,'order','/actuator/circuitbreakerevents/inventory',s.adminToken)
            if(response.status!=200) { s.observerErrors.incrementAndGet(); return }
            eventList(response).each { Map e ->
                String id=fingerprint(e); long stamp=eventTime(e.creationTime)
                if(stamp>=s.startedEpoch && !s.baselineEvents.contains(id) && e.type in ['STATE_TRANSITION','NOT_PERMITTED']) {
                    s.events.putIfAbsent(id,[creationTime:e.creationTime.toString(),epochMs:stamp,type:e.type.toString(),stateTransition:e.stateTransition?.toString() ?: '',circuitBreakerName:e.circuitBreakerName?.toString() ?: 'inventory'])
                }
            }
        } catch(Exception ignored) { s.observerErrors.incrementAndGet() }
    }
    static List chain(Map s) {
        List sorted=s.events.values().findAll{it.type=='STATE_TRANSITION'}.sort{a,b->a.epochMs<=>b.epochMs}
        List result=[]; String[] wanted=['CLOSED_TO_OPEN','OPEN_TO_HALF_OPEN','HALF_OPEN_TO_CLOSED']
        sorted.each { e -> if(result.size()<3 && e.stateTransition==wanted[result.size()])result.add(e) }
        return result
    }
    static boolean waitFor(Map s,long deadline,Closure condition) {
        while(now()<deadline) { if(condition.call())return true; Thread.sleep(250) }
        return condition.call()
    }
    static void record(Map s,Map entry,int status,def body,long latency,String source,String transport=null) {
        Map safe=[key:entry.key,phase:entry.phase,source:source,httpStatus:status,latencyMs:latency]
        if(transport)safe.transportError=transport
        if(status==202 && body instanceof Map && body.id) {
            String id=body.id.toString()
            if(!(id==~ /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/)) {
                failure(s,'INVALID_ORDER_ID');safe.errorCode='INVALID_ORDER_ID'
            } else {
                String earlier=s.admissions.putIfAbsent(entry.key,id)
                if(earlier!=null && earlier!=id)failure(s,'IDEMPOTENCY_ID_MISMATCH')
                entry.orderId=id;safe.orderId=id
                s.orders.putIfAbsent(id,[id:id,key:entry.key,phase:entry.phase,status:'ACCEPTED',createdEpoch:entry.createdEpoch])
            }
        } else if(status!=0 && status!=429 && status<500) {
            failure(s,'PERMANENT_ADMISSION_HTTP_'+status)
        }
        s.requests.add(safe)
    }
    static void recover(Map s,long deadline) {
        s.attempts.values().findAll{!it.orderId}.sort{a,b->a.key<=>b.key}.each { Map entry ->
            for(int n=1;n<=3 && !entry.orderId && now()<deadline;n++) {
                // The explicit replay uses EXACTLY the original key and JSON body.
                long begin=now();HttpURLConnection c=null;Map result
                try {
                    c=(HttpURLConnection)new URL("${s.config.protocol}://${s.config.host}:${s.config.orderPort}/api/orders").openConnection()
                    c.connectTimeout=2000;c.readTimeout=5000;c.requestMethod='POST';c.doOutput=true
                    c.setRequestProperty('Authorization','Bearer '+s.buyerToken)
                    c.setRequestProperty('Idempotency-Key',entry.key)
                    c.setRequestProperty('Content-Type','application/json; charset=UTF-8')
                    c.setRequestProperty('Connection','close')
                    c.outputStream.withCloseable{it.write(entry.body.getBytes(StandardCharsets.UTF_8))}
                    int code=c.responseCode;def stream=code>=400?c.errorStream:c.inputStream;def body=null
                    if(stream!=null) { String text=stream.withCloseable{it.getText('UTF-8')};if(text){try{body=new JsonSlurper().parseText(text)}catch(Exception ignored){}} }
                    result=[status:code,body:body]
                } catch(Exception ex) { result=[status:0,transportError:ex.class.simpleName] }
                finally { if(c!=null)c.disconnect() }
                record(s,entry,result.status as int,result.body,now()-begin,'same-key-recovery',result.transportError as String)
                if(!entry.orderId)Thread.sleep(Math.min(1000L,250L*n))
            }
            if(!entry.orderId)failure(s,'UNACKNOWLEDGED_LOGICAL_ORDER')
        }
    }
    static void resetFault(Map s) {
        if(!s.adminToken)return
        def reset=request(s,'inventory','/demo/faults',s.adminToken,'POST',JsonOutput.toJson([delayMs:0,fail:false]))
        s.faultReset=[time:Instant.now().toString(),httpStatus:reset.status]
        if(reset.status!=200 || reset.body?.delayMs!=0 || reset.body?.fail!=false)failure(s,'FAULT_RESET_FAILED')
    }
    static void finish(Map s) {
        resetFault(s)
        if(s.observer!=null) {s.observer.shutdownNow();s.observer.awaitTermination(7,TimeUnit.SECONDS)}
        if(s.adminToken)observe(s)
        List transitions=chain(s)
        if(s.ready && transitions.size()!=3)failure(s,'MISSING_ORDERED_OPEN_HALF_OPEN_CLOSED_EVENTS')
        if(s.ready && s.lastState!='CLOSED')failure(s,'FINAL_BREAKER_NOT_CLOSED')
        if(s.ready && s.orders.values().any{!(it.status in ['COMPLETED','REJECTED'])})failure(s,'NON_TERMINAL_ORDER')
        if(s.ready && s.admissions.size()!=s.config.plannedLoad+3)failure(s,'LOGICAL_ORDER_COUNT_MISMATCH')
        if(s.ready && !s.orders.values().any{it.phase=='probe'&&it.status=='COMPLETED'})failure(s,'NO_HEALTHY_RECOVERY_PROBE_COMPLETED')
        List events=s.events.values().toList().sort{a,b->a.epochMs<=>b.epochMs}
        List orders=s.orders.values().toList().sort{a,b->a.id<=>b.id}
        List requests=s.requests.toList()
        def statusCounts=requests.groupBy{it.httpStatus.toString()}.collectEntries{k,v->[(k):v.size()]}
        Map summary=[status:s.failures.isEmpty()?'PASS':'FAIL',runId:s.runId,startedAt:s.startedAt,finishedAt:Instant.now().toString(),scenario:[fail:s.config.fail,delayMs:s.config.delayMs],
            plannedLoadOrders:s.config.plannedLoad,plannedProbeOrders:3,logicalOrders:s.attempts.size(),acknowledgedOrders:s.admissions.size(),actualCheckoutPostAttempts:requests.size(),httpStatusCounts:statusCounts,
            completed:orders.count{it.status=='COMPLETED'},rejected:orders.count{it.status=='REJECTED'},pending:orders.count{!(it.status in ['COMPLETED','REJECTED'])},
            initialState:s.initialState,finalState:s.lastState,orderedTransitionProof:transitions,notPermittedObserved:events.any{it.type=='NOT_PERMITTED'},observerErrorCount:s.observerErrors.get(),
            faultReset:s.faultReset,failures:s.failures.toList(),requests:requests,orders:orders,events:events,observations:s.observations.toList(),
            latencyScope:'Native checkout HTTP samples measure admission only. orders[].endToEndMs measures elapsed time until a terminal Saga status was observed.']
        Files.createDirectories(s.output)
        Files.write(s.output.resolve('summary.json'),JsonOutput.prettyPrint(JsonOutput.toJson(summary)).getBytes(StandardCharsets.UTF_8))
        // Remove credentials from shared properties after the last HTTP request.
        s.remove('adminToken');s.remove('buyerToken')
        return
    }
}

String phase=Parameters.trim()
Map state=props.get(FlashSaleCb.KEY) as Map
def sr=binding.hasVariable('SampleResult')?binding.getVariable('SampleResult'):null
try {
    switch(phase) {
        case 'initialize':
            state=new ConcurrentHashMap()
            state.runId=UUID.randomUUID().toString();state.startedAt=Instant.now().toString();state.startedEpoch=FlashSaleCb.now()
            state.failures=new ConcurrentLinkedQueue();state.requests=new ConcurrentLinkedQueue();state.observations=new ConcurrentLinkedQueue()
            state.events=new ConcurrentHashMap();state.attempts=new ConcurrentHashMap();state.admissions=new ConcurrentHashMap();state.orders=new ConcurrentHashMap()
            state.observerErrors=new AtomicInteger();state.sequence=new AtomicInteger();state.baselineEvents=ConcurrentHashMap.newKeySet()
            state.ready=false;state.configured=false;state.lastState='UNKNOWN'
            String out=props.getProperty('outputDirectory','').trim()
            state.output=Paths.get(out?:('.local/jmeter-cb/'+state.runId)).toAbsolutePath().normalize()
            props.put(FlashSaleCb.KEY,state)
            Map cfg=[protocol:props.getProperty('protocol','http'),host:props.getProperty('host','127.0.0.1'),productId:props.getProperty('productId','22222222-2222-2222-2222-222222222222')]
            ['auth':8081,'inventory':8082,'payment':8083,'order':8084].each{k,v->cfg[k+'Port']=Integer.parseInt(props.getProperty(k+'Port',v.toString()))}
            cfg.threads=Integer.parseInt(props.getProperty('threads','8'));cfg.loops=Integer.parseInt(props.getProperty('loops','4'))
            cfg.plannedLoad=cfg.threads*cfg.loops;cfg.timeoutSeconds=Integer.parseInt(props.getProperty('timeoutSeconds','180'))
            cfg.delayMs=Integer.parseInt(props.getProperty('delayMs','1200'));cfg.fail=Boolean.parseBoolean(props.getProperty('fail','true'))
            state.config=cfg
            if(!(cfg.protocol in ['http','https']) || cfg.threads<1 || cfg.threads>32 || cfg.loops<1 || cfg.plannedLoad>96 || cfg.timeoutSeconds<30 || cfg.timeoutSeconds>600 || cfg.delayMs<0 || cfg.delayMs>10000)throw new IllegalArgumentException('Invalid bounded demo configuration')
            UUID.fromString(cfg.productId)
            vars.put('adminLoginBody',JsonOutput.toJson([username:props.getProperty('adminUsername','admin'),password:props.getProperty('adminPassword','Admin@123')]))
            vars.put('buyerLoginBody',JsonOutput.toJson([username:props.getProperty('customerUsername','alice'),password:props.getProperty('customerPassword','Demo@123')]))
            vars.put('runId',state.runId)
            // Never let HttpClient silently replay a checkout POST under a different test attempt.
            props.setProperty('httpclient4.retrycount','0');props.setProperty('httpclient4.request_sent_retry_enabled','false')
            state.configured=true
            break
        case 'login-admin':
        case 'login-buyer':
            String who=phase=='login-admin'?'admin':'buyer'
            String token=vars.get(who=='admin'?'adminAccessToken':'buyerAccessToken')
            if(prev.responseCode!='200' || !token || token=='__MISSING__')FlashSaleCb.failure(state,'LOGIN_'+who.toUpperCase()+'_FAILED')
            else state[who+'Token']=token
            FlashSaleCb.scrub(prev)
            vars.remove(who=='admin'?'adminAccessToken':'buyerAccessToken');vars.remove(who+'LoginBody')
            break
        case 'arm-fault':
            if(!state.adminToken || !state.buyerToken)break
            FlashSaleCb.resetFault(state)
            def initial=FlashSaleCb.request(state,'order','/actuator/circuitbreakers',state.adminToken)
            state.initialState=initial.body?.circuitBreakers?.inventory?.state?.toString()?:'UNKNOWN'
            if(initial.status!=200 || state.initialState!='CLOSED') {FlashSaleCb.failure(state,'INITIAL_BREAKER_MUST_BE_CLOSED_RESTART_DEMO_STACK');break}
            def catalog=FlashSaleCb.request(state,'inventory','/api/products/'+state.config.productId,state.buyerToken)
            if(catalog.status!=200 || (catalog.body?.availableStock?:0)<state.config.plannedLoad+3) {FlashSaleCb.failure(state,'INSUFFICIENT_FRESH_STOCK_FOR_LOAD_AND_THREE_PROBES');break}
            def baseline=FlashSaleCb.request(state,'order','/actuator/circuitbreakerevents/inventory',state.adminToken)
            if(baseline.status!=200){FlashSaleCb.failure(state,'BASELINE_EVENTS_UNAVAILABLE');break}
            FlashSaleCb.eventList(baseline).each{state.baselineEvents.add(FlashSaleCb.fingerprint(it))}
            ThreadFactory factory={Runnable r->Thread t=new Thread(r,'flashsale-jmeter-observer-'+state.runId);t.daemon=true;t} as ThreadFactory
            state.observer=Executors.newSingleThreadScheduledExecutor(factory)
            state.observer.scheduleWithFixedDelay({FlashSaleCb.observe(state)} as Runnable,0,500,TimeUnit.MILLISECONDS)
            def fault=FlashSaleCb.request(state,'inventory','/demo/faults',state.adminToken,'POST',JsonOutput.toJson([delayMs:state.config.delayMs,fail:state.config.fail]))
            if(fault.status!=200 || fault.body?.delayMs!=state.config.delayMs || fault.body?.fail!=state.config.fail) {FlashSaleCb.failure(state,'FAULT_ENABLE_FAILED_DEMO_ENABLED_REQUIRED');break}
            state.ready=true
            break
        case 'prepare-load':
        case 'prepare-probe':
            String kind=phase=='prepare-load'?'load':'probe'
            String key='jmeter-cb-'+state.runId+'-'+kind+'-'+state.sequence.incrementAndGet()
            Map entry=new ConcurrentHashMap([key:key,phase:kind,body:JsonOutput.toJson([productId:state.config.productId,quantity:1]),createdEpoch:FlashSaleCb.now()])
            state.attempts[key]=entry
            vars.put('checkoutKey',key);vars.put('orderBody',entry.body);vars.put('buyerToken',state.buyerToken);vars.put('runId',state.runId);vars.remove('orderId')
            break
        case 'record-order':
            String key=vars.get('checkoutKey');Map entry=state.attempts[key]
            int code=prev.responseCode==~ /\d{3}/?Integer.parseInt(prev.responseCode):0
            def body=null
            try{body=new JsonSlurper().parseText(prev.responseDataAsString)}catch(Exception ignored){}
            FlashSaleCb.record(state,entry,code,body,prev.time,'native-http',code==0?'HTTP_TRANSPORT_FAILURE':null)
            if(entry.orderId)vars.put('orderId',entry.orderId)
            FlashSaleCb.scrub(prev);vars.remove('buyerToken')
            break
        case 'monitor-reset-recover':
            long deadline=FlashSaleCb.now()+state.config.timeoutSeconds*1000L;state.recoveryDeadline=deadline
            if(!FlashSaleCb.waitFor(state,Math.min(deadline,FlashSaleCb.now()+40000L),{state.lastState=='OPEN' || !FlashSaleCb.chain(state).isEmpty()}))FlashSaleCb.failure(state,'OPEN_NOT_OBSERVED')
            FlashSaleCb.resetFault(state)
            FlashSaleCb.recover(state,deadline)
            if(!FlashSaleCb.waitFor(state,Math.min(deadline,FlashSaleCb.now()+30000L),{state.lastState=='HALF_OPEN' || FlashSaleCb.chain(state).size()==3}))FlashSaleCb.failure(state,'HALF_OPEN_OR_RECOVERY_NOT_OBSERVED_BEFORE_PROBES')
            break
        case 'await-orders-and-closed':
            long deadline=state.recoveryDeadline as long
            FlashSaleCb.recover(state,deadline)
            while(FlashSaleCb.now()<deadline) {
                List pending=state.orders.values().findAll{!(it.status in ['COMPLETED','REJECTED'])}
                for(Map order:pending) {
                    if(FlashSaleCb.now()>=deadline)break
                    def got=FlashSaleCb.request(state,'order','/api/orders/'+order.id,state.buyerToken)
                    if(got.status==200 && got.body?.id?.toString()==order.id && got.body.status in ['COMPLETED','REJECTED']) {
                        order.status=got.body.status.toString();order.failureCode=got.body.failureCode
                        order.endToEndMs=FlashSaleCb.now()-(order.createdEpoch as long)
                    }
                }
                if(state.orders.values().every{it.status in ['COMPLETED','REJECTED']} && FlashSaleCb.chain(state).size()==3 && state.lastState=='CLOSED')break
                Thread.sleep(500)
            }
            break
        case 'teardown':
            if(state!=null)FlashSaleCb.finish(state)
            break
        default:throw new IllegalArgumentException('Unknown test phase')
    }
    if(sr!=null) {
        boolean okay=state!=null && state.failures.isEmpty()
        sr.setSuccessful(okay);sr.setResponseCode(okay?'200':'500');sr.setResponseMessage(okay?'Phase complete':'See safe summary.json')
        sr.setResponseData(JsonOutput.toJson([phase:phase,failures:state?.failures?.size()?:0]),'UTF-8')
    }
} catch(Throwable problem) {
    if(state!=null)FlashSaleCb.failure(state,'PHASE_'+phase.toUpperCase().replace('-','_')+'_'+problem.class.simpleName)
    if(phase.startsWith('login-') || phase=='record-order')FlashSaleCb.scrub(prev)
    if(sr!=null){sr.setSuccessful(false);sr.setResponseCode('500');sr.setResponseMessage('Phase failed; see safe summary')}
    log.warn('Flash sale JMeter phase failed: {} ({})',phase,problem.class.simpleName)
    if(phase=='teardown' && state!=null) {
        // Even unexpected cleanup errors must produce a safe machine-readable failure artifact.
        try{Files.createDirectories(state.output);Files.write(state.output.resolve('summary.json'),JsonOutput.toJson([status:'FAIL',runId:state.runId,failures:state.failures.toList()]).getBytes(StandardCharsets.UTF_8))}catch(Exception ignored){}
    }
}
