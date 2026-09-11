package com.openggf.tools.audio.completerun;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.*;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical JSON writer and exact token-stream reader for complete-run audio records. */
final class CompleteRunAudioJson {
    private static final int MAX_FRAME_ITEMS = 65_536;
    static final JsonFactory FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100)
                    .maxStringLength(1_048_576).maxNumberLength(64).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final ObjectMapper WRITER = new ObjectMapper(FACTORY);

    private CompleteRunAudioJson() { }

    static String writeRecord(CompleteRunAudioTrace.Record record) throws IOException {
        StringWriter out = new StringWriter();
        try (JsonGenerator json = FACTORY.createGenerator(out)) {
            json.writeStartObject();
            json.writeStringField("type", type(record));
            json.writeFieldName("value");
            WRITER.writeValue(json, record);
            json.writeEndObject();
        }
        return out.toString();
    }

    /** Canonical parity projection; validated native callback diagnostics deliberately do not participate. */
    static String writeSemanticRecord(CompleteRunAudioTrace.Record record) throws IOException {
        if (!(record instanceof Baseline) && !(record instanceof CutoffFrontier)
                && !(record instanceof Frame)) return writeRecord(record);
        Map<String,Object> value;
        String type;
        if (record instanceof Baseline baseline) {
            value = new LinkedHashMap<>();
            value.put("absoluteFrame", baseline.absoluteFrame());
            value.put("state", baseline.state());
            value.put("roleOwners", baseline.roleOwners());
            value.put("frontier", boundarySemanticFields(baseline.frontier()));
            type = "baseline";
        } else if (record instanceof CutoffFrontier frontier) {
            value = frontierSemanticFields(frontier);
            type = "cutoff_frontier";
        } else {
            Frame frame = (Frame) record;
            value = new LinkedHashMap<>();
            value.put("absoluteFrame", frame.absoluteFrame());
            value.put("segment", frame.segment());
            value.put("lag", frame.lag());
            value.put("requests", frame.requests());
            value.put("decisions", frame.decisions());
            value.put("services", frame.services());
            value.put("postRowState", frame.postRowState());
            value.put("chipEvents", frame.chipEvents());
            type = "frame";
        }
        StringWriter out = new StringWriter();
        try (JsonGenerator json = FACTORY.createGenerator(out)) {
            json.writeStartObject(); json.writeStringField("type", type);
            json.writeFieldName("value"); WRITER.writeValue(json, value); json.writeEndObject();
        }
        return out.toString();
    }

    private static Map<String,Object> frontierSemanticFields(CutoffFrontier frontier) {
        Map<String,Object> value = boundarySemanticFields(frontier.frontier());
        value.put("terminalState", frontier.terminalState());
        return value;
    }

    private static Map<String,Object> boundarySemanticFields(BoundaryFrontier frontier) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("activeStack", frontier.activeStack());
        value.put("pendingDescendants", frontier.pendingDescendants());
        value.put("rawChipEvents", frontier.rawChipEvents());
        value.put("ymPort0Latch", frontier.ymPort0Latch());
        value.put("ymPort1Latch", frontier.ymPort1Latch());
        return value;
    }

    static String frontierCapabilityProjection(CutoffFrontier frontier) {
        try {
            Map<String,Object> value = frontierSemanticFields(frontier);
            value.remove("terminalState");
            StringWriter out = new StringWriter();
            try (JsonGenerator json = FACTORY.createGenerator(out)) {
                WRITER.writeValue(json, value);
            }
            return out.toString();
        } catch (IOException impossible) {
            throw new IllegalArgumentException("cannot canonicalize cutoff-frontier capability", impossible);
        }
    }

    static String writeNativeCutoffDiagnostics(CutoffNativeDiagnostics diagnostics) {
        try {
            StringWriter out = new StringWriter();
            try (JsonGenerator json = FACTORY.createGenerator(out)) {
                WRITER.writeValue(json, diagnostics);
            }
            return out.toString();
        } catch (IOException impossible) {
            throw new IllegalArgumentException("cannot canonicalize native cutoff diagnostics", impossible);
        }
    }

    static String writeMetadata(Metadata metadata) throws IOException {
        StringWriter out = new StringWriter();
        try (JsonGenerator json = FACTORY.createGenerator(out)) {
            writeMetadata(json, metadata);
        }
        return out.toString();
    }

    static void writeMetadata(JsonGenerator json, Metadata metadata) throws IOException {
        json.writeStartObject();
        json.writeStringField("schema", metadata.schema());
        json.writeStringField("profileId", metadata.profileId());
        json.writeFieldName("fixture"); WRITER.writeValue(json, metadata.fixture());
        json.writeStringField("producerKind", metadata.producerKind().name());
        json.writeFieldName("producerRuntimeIdentity"); WRITER.writeValue(json, metadata.producerRuntimeIdentity());
        json.writeFieldName("observerRuntimeIdentity"); writeObserverIdentity(json, metadata.observerRuntimeIdentity());
        json.writeFieldName("observerProof"); WRITER.writeValue(json, metadata.observerProof());
        json.writeFieldName("chunkPolicy"); WRITER.writeValue(json, metadata.chunkPolicy());
        json.writeFieldName("hardwareRoles"); WRITER.writeValue(json, metadata.hardwareRoles());
        json.writeFieldName("stateInventory"); WRITER.writeValue(json, metadata.stateInventory());
        json.writeFieldName("comparisonLayerInventory"); writeComparisonLayerInventory(json,
                metadata.comparisonLayerInventory());
        json.writeFieldName("producerObservationInventory"); writeProducerObservationInventory(json,
                metadata.producerObservationInventory());
        json.writeEndObject();
    }

    private static void writeObserverIdentity(JsonGenerator json, ObserverRuntimeIdentity identity)
            throws IOException {
        json.writeStartObject();
        if (identity instanceof CallbackObserverIdentity callback) {
            json.writeStringField("kind", "CALLBACK");
            json.writeStringField("id", callback.id());
        } else if (identity instanceof BufferedNativeObserverIdentity nativeIdentity) {
            json.writeStringField("kind", "BUFFERED_NATIVE");
            json.writeStringField("abiName", nativeIdentity.abiName());
            json.writeNumberField("abiVersion", nativeIdentity.abiVersion());
            json.writeNumberField("eventSize", nativeIdentity.eventSize());
            json.writeNumberField("configSize", nativeIdentity.configSize());
            json.writeNumberField("kindSize", nativeIdentity.kindSize());
            json.writeNumberField("hookSize", nativeIdentity.hookSize());
            json.writeNumberField("rangeSize", nativeIdentity.rangeSize());
            json.writeNumberField("capacity", nativeIdentity.capacity());
            json.writeStringField("installationId", nativeIdentity.installationId());
            json.writeStringField("coreId", nativeIdentity.coreId());
            json.writeStringField("coreBuildId", nativeIdentity.coreBuildId());
            json.writeStringField("watchMaskSha256", nativeIdentity.watchMaskSha256());
            json.writeStringField("serviceManifestSha256", nativeIdentity.serviceManifestSha256());
            json.writeBooleanField("enabled", nativeIdentity.enabled());
            json.writeNumberField("maximumFrameOccupancy", nativeIdentity.maximumFrameOccupancy());
            json.writeNumberField("overflowCount", nativeIdentity.overflowCount());
        } else {
            throw new IllegalArgumentException("unknown observer runtime identity type");
        }
        json.writeEndObject();
    }

    static CompleteRunAudioTrace.Record readRecord(String line) {
        try (JsonParser p = FACTORY.createParser(line)) {
            start(p, "record"); field(p, "type"); String type = text(p, "record type"); field(p, "value");
            CompleteRunAudioTrace.Record record = switch (type) {
                case "baseline" -> baseline(p);
                case "frame" -> frame(p);
                case "lifecycle" -> lifecycle(p);
                case "cutoff_frontier" -> cutoffFrontier(p);
                case "terminal" -> terminal(p);
                default -> throw invalid("unknown record type: " + type);
            };
            end(p, "record"); eof(p, "record");
            return record;
        } catch (IOException failure) { throw invalid("invalid complete-run record JSON", failure); }
    }

    static Metadata readMetadata(JsonParser p) throws IOException {
        startCurrent(p, "metadata");
        field(p,"schema"); String schema=text(p,"metadata schema");
        field(p,"profileId"); String profile=text(p,"metadata profile ID");
        field(p,"fixture"); CompleteRunFixture fixture=fixture(p);
        field(p,"producerKind"); ProducerKind kind=enumValue(p,ProducerKind.class,"producer kind");
        field(p,"producerRuntimeIdentity"); ProducerRuntimeIdentity runtime=runtime(p);
        field(p,"observerRuntimeIdentity"); ObserverRuntimeIdentity observerIdentity=observerIdentity(p);
        field(p,"observerProof"); ObserverProof proof=proof(p);
        field(p,"chunkPolicy"); ChunkPolicy policy=policy(p);
        field(p,"hardwareRoles"); List<HardwareRole> roles=enums(p,HardwareRole.class,"hardware roles");
        field(p,"stateInventory"); StateInventory inventory=inventory(p);
        field(p,"comparisonLayerInventory"); ComparisonLayerInventory layers=comparisonLayerInventory(p);
        field(p,"producerObservationInventory"); ProducerObservationInventory observed=
                producerObservationInventory(p);
        end(p,"metadata"); return new Metadata(schema,profile,fixture,kind,runtime,observerIdentity,proof,policy,
                roles,inventory,layers,observed);
    }

    private static void writeComparisonLayerInventory(JsonGenerator json, ComparisonLayerInventory inventory)
            throws IOException {
        json.writeStartArray();
        for (ComparisonLayerClaim claim : inventory.claims()) {
            json.writeStartObject();
            json.writeStringField("layer", claim.layer().name());
            json.writeStringField("status", claim.status().name());
            if (claim.reason() == null) json.writeNullField("reason");
            else json.writeStringField("reason", claim.reason());
            json.writeEndObject();
        }
        json.writeEndArray();
    }

    private static ComparisonLayerInventory comparisonLayerInventory(JsonParser p) throws IOException {
        array(p, "comparison layer inventory");
        List<ComparisonLayerClaim> claims = new ArrayList<>();
        while (p.nextToken() != JsonToken.END_ARRAY) {
            bound(claims, "comparison layer claim");
            startCurrent(p, "comparison layer claim");
            field(p, "layer"); ComparisonLayer layer = enumValue(p, ComparisonLayer.class, "comparison layer");
            field(p, "status"); ComparisonLayerStatus status = enumValue(p, ComparisonLayerStatus.class,
                    "comparison layer status");
            field(p, "reason"); String reason = nullableText(p, "comparison layer reason");
            end(p, "comparison layer claim");
            claims.add(new ComparisonLayerClaim(layer, status, reason));
        }
        return new ComparisonLayerInventory(claims);
    }

    private static void writeProducerObservationInventory(JsonGenerator json,
            ProducerObservationInventory inventory) throws IOException {
        json.writeStartArray();
        for (ProducerObservationClaim claim : inventory.claims()) {
            json.writeStartObject();
            json.writeStringField("layer", claim.layer().name());
            json.writeStringField("status", claim.status().name());
            if (claim.reason() == null) json.writeNullField("reason");
            else json.writeStringField("reason", claim.reason());
            json.writeEndObject();
        }
        json.writeEndArray();
    }

    private static ProducerObservationInventory producerObservationInventory(JsonParser p) throws IOException {
        array(p, "producer observation inventory");
        List<ProducerObservationClaim> claims = new ArrayList<>();
        while (p.nextToken() != JsonToken.END_ARRAY) {
            bound(claims, "producer observation claim");
            startCurrent(p, "producer observation claim");
            field(p, "layer"); ComparisonLayer layer = enumValue(p, ComparisonLayer.class,
                    "producer observation layer");
            field(p, "status"); ObservationStatus status = enumValue(p, ObservationStatus.class,
                    "producer observation status");
            field(p, "reason"); String reason = nullableText(p, "producer observation reason");
            end(p, "producer observation claim");
            claims.add(new ProducerObservationClaim(layer, status, reason));
        }
        return new ProducerObservationInventory(claims);
    }

    private static Baseline baseline(JsonParser p) throws IOException { startCurrent(p,"baseline"); field(p,"absoluteFrame"); int frame=intValue(p,"baseline frame"); field(p,"state"); NormalizedState state=nullableState(p); field(p,"roleOwners"); List<RoleOwner> owners=nullableRoleOwners(p); field(p,"frontier"); BoundaryFrontier frontier=boundaryFrontier(p); end(p,"baseline"); return new Baseline(frame,state,owners,frontier); }
    private static BoundaryFrontier boundaryFrontier(JsonParser p)throws IOException{startCurrent(p,"boundary frontier");field(p,"activeStack");List<CutoffService> active=nullableCutoffServices(p);field(p,"pendingDescendants");List<CutoffService> pending=nullableCutoffServices(p);field(p,"rawChipEvents");List<ChipEvent> raw=nullableChips(p);field(p,"nativeDiagnostics");CutoffNativeDiagnostics diagnostics=cutoffNativeDiagnostics(p);field(p,"ymPort0Latch");Integer latch0=nullableInt(p,"YM port-zero latch");field(p,"ymPort1Latch");Integer latch1=nullableInt(p,"YM port-one latch");end(p,"boundary frontier");return new BoundaryFrontier(active,pending,raw,diagnostics,latch0,latch1);}
    private static Frame frame(JsonParser p) throws IOException { startCurrent(p,"frame"); field(p,"absoluteFrame"); int absolute=intValue(p,"frame"); field(p,"segment"); String segment=nullableText(p,"frame segment"); field(p,"lag"); Boolean lag=nullableBoolean(p,"frame lag"); field(p,"requests"); List<Request> requests=nullableRequests(p); field(p,"decisions");List<Decision> decisions=nullableDecisions(p);field(p,"services"); List<DriverService> services=nullableServices(p);field(p,"postRowState");NormalizedState state=nullableState(p);field(p,"chipEvents");List<ChipEvent> chips=nullableChips(p);field(p,"nativeDiagnostics");FrameNativeDiagnostics diagnostics=frameNativeDiagnostics(p);end(p,"frame"); return new Frame(absolute,segment,lag,requests,decisions,services,state,chips,diagnostics); }
    private static FrameNativeDiagnostics frameNativeDiagnostics(JsonParser p)throws IOException{if(p.currentToken()==JsonToken.VALUE_NULL)return null;startCurrent(p,"frame native diagnostics");field(p,"services");List<FrontierService> services=frontierServices(p);field(p,"rawChipInventory");List<FrontierOwnedChip> chips=frontierOwnedChips(p);field(p,"rawSnapshotInventory");List<FrontierOwnedSnapshot> snapshots=frontierOwnedSnapshots(p);field(p,"resets");List<NativeResetDiagnostic> resets=nativeResets(p);field(p,"managedCorrelations");List<NativeManagedCorrelation> correlations=nativeManagedCorrelations(p);field(p,"deferredServiceBegins");List<NativeDeferredServiceBegin> deferred=nativeDeferredServiceBegins(p);field(p,"rawAncestryTransitionInventory");List<FrontierOwnedAncestryTransition> transitions=frontierOwnedAncestryTransitions(p);end(p,"frame native diagnostics");return new FrameNativeDiagnostics(services,chips,snapshots,resets,correlations,deferred,transitions);}
    private static List<NativeResetDiagnostic> nativeResets(JsonParser p)throws IOException{array(p,"native resets");List<NativeResetDiagnostic> result=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY){if(result.size()==8)throw invalid("native reset bound exceeded");startCurrent(p,"native reset");field(p,"serviceToken");long token=longValue(p,"native reset token");field(p,"power");boolean power=booleanValue(p,"native reset power");end(p,"native reset");result.add(new NativeResetDiagnostic(token,power));}return List.copyOf(result);}
    private static List<NativeManagedCorrelation> nativeManagedCorrelations(JsonParser p)throws IOException{array(p,"native managed correlations");List<NativeManagedCorrelation> result=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY){if(result.size()==CompleteRunAudioTrace.MAX_NATIVE_FRAME_EVENTS)throw invalid("native managed-correlation bound exceeded");startCurrent(p,"native managed correlation");field(p,"managedCorrelationOrdinal");long ordinal=longValue(p,"managed correlation ordinal");field(p,"events");List<NativeManagedEvent> events=nativeManagedEvents(p);end(p,"native managed correlation");result.add(new NativeManagedCorrelation(ordinal,events));}return List.copyOf(result);}
    private static List<NativeManagedEvent> nativeManagedEvents(JsonParser p)throws IOException{array(p,"native managed events");List<NativeManagedEvent> result=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY){if(result.size()==2)throw invalid("native managed correlation has too many events");startCurrent(p,"native managed event");field(p,"coordinate");long coordinate=longValue(p,"native managed-event coordinate");field(p,"ordinal");long ordinal=longValue(p,"native managed-event ordinal");field(p,"sourceCpu");String source=text(p,"native managed-event source");field(p,"pc");int pc=intValue(p,"native managed-event PC");field(p,"eventKind");int kind=intValue(p,"native managed-event kind");field(p,"value");int value=intValue(p,"native managed-event value");field(p,"serviceToken");long token=longValue(p,"native managed-event service token");field(p,"parentToken");long parent=longValue(p,"native managed-event parent token");field(p,"serviceKind");int service=intValue(p,"native managed-event service kind");field(p,"depth");int depth=intValue(p,"native managed-event depth");field(p,"hookToken");int hook=intValue(p,"native managed-event hook token");field(p,"flags");int flags=intValue(p,"native managed-event flags");field(p,"terminal");boolean terminal=booleanValue(p,"native managed-event terminal marker");end(p,"native managed event");result.add(new NativeManagedEvent(coordinate,ordinal,source,pc,kind,value,token,parent,service,depth,hook,flags,terminal));}return List.copyOf(result);}
    private static List<NativeDeferredServiceBegin> nativeDeferredServiceBegins(JsonParser p)throws IOException{array(p,"native deferred service begins");List<NativeDeferredServiceBegin> result=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY){if(!result.isEmpty())throw invalid("native deferred service-begin bound exceeded");result.add(nativeDeferredServiceBegin(p));}return List.copyOf(result);}
    private static NativeDeferredServiceBegin nullableNativeDeferredServiceBegin(JsonParser p)throws IOException{return p.currentToken()==JsonToken.VALUE_NULL?null:nativeDeferredServiceBegin(p);}
    private static NativeDeferredServiceBegin nativeDeferredServiceBegin(JsonParser p)throws IOException{startCurrent(p,"native deferred service begin");field(p,"blockerToken");long blocker=longValue(p,"native deferred blocker token");field(p,"blockerParentToken");long parent=longValue(p,"native deferred blocker parent");field(p,"blockerKind");int blockerKind=intValue(p,"native deferred blocker kind");field(p,"blockerDepth");int depth=intValue(p,"native deferred blocker depth");field(p,"targetKind");int target=intValue(p,"native deferred target kind");field(p,"hookToken");int hook=intValue(p,"native deferred hook token");field(p,"sourceCpu");int cpu=intValue(p,"native deferred source CPU");field(p,"pc");int pc=intValue(p,"native deferred PC");field(p,"firstCoordinate");long firstCoordinate=longValue(p,"native deferred first coordinate");field(p,"latestCoordinate");long latestCoordinate=longValue(p,"native deferred latest coordinate");field(p,"firstOrdinal");long firstOrdinal=longValue(p,"native deferred first ordinal");field(p,"latestOrdinal");long latestOrdinal=longValue(p,"native deferred latest ordinal");field(p,"observationCount");int observations=intValue(p,"native deferred observation count");field(p,"consumed");boolean consumed=booleanValue(p,"native deferred consumed status");field(p,"consumedToken");long consumedToken=longValue(p,"native deferred consumed token");field(p,"consumeCoordinate");long consumeCoordinate=longValue(p,"native deferred consume coordinate");end(p,"native deferred service begin");return new NativeDeferredServiceBegin(blocker,parent,blockerKind,depth,target,hook,cpu,pc,firstCoordinate,latestCoordinate,firstOrdinal,latestOrdinal,observations,consumed,consumedToken,consumeCoordinate);}
    private static Lifecycle lifecycle(JsonParser p) throws IOException { startCurrent(p,"lifecycle"); field(p,"ordinal"); long ordinal=longValue(p,"lifecycle ordinal"); field(p,"absoluteFrame"); int frame=intValue(p,"lifecycle frame"); field(p,"kind"); String kind=text(p,"lifecycle kind"); field(p,"details"); Map<String,Object> details=objectValues(p,"lifecycle details"); field(p,"ownershipTransitions"); List<LifecycleOwnership> ownership=nullableLifecycleOwnership(p); end(p,"lifecycle"); return new Lifecycle(ordinal,frame,kind,details,ownership); }
    private static CutoffFrontier cutoffFrontier(JsonParser p) throws IOException { startCurrent(p,"cutoff frontier"); field(p,"activeStack"); List<CutoffService> active=nullableCutoffServices(p); field(p,"pendingDescendants"); List<CutoffService> pending=nullableCutoffServices(p);field(p,"rawChipEvents");List<ChipEvent> raw=nullableChips(p); field(p,"nativeDiagnostics");CutoffNativeDiagnostics diagnostics=cutoffNativeDiagnostics(p);field(p,"ymPort0Latch"); Integer latch0=nullableInt(p,"YM port-zero latch"); field(p,"ymPort1Latch"); Integer latch1=nullableInt(p,"YM port-one latch"); field(p,"terminalState"); NormalizedState state=nullableState(p); end(p,"cutoff frontier"); return new CutoffFrontier(active,pending,raw,diagnostics,latch0,latch1,state); }
    private static List<CutoffService> cutoffServices(JsonParser p)throws IOException{array(p,"cutoff services");List<CutoffService> result=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY){if(result.size()==CompleteRunAudioTrace.MAX_CUTOFF_SERVICES)throw invalid("cutoff service bound exceeded");result.add(cutoffService(p));}return List.copyOf(result);}
    private static List<CutoffService> nullableCutoffServices(JsonParser p)throws IOException{return p.currentToken()==JsonToken.VALUE_NULL?null:cutoffServices(p);}
    private static CutoffService cutoffService(JsonParser p)throws IOException{startCurrent(p,"cutoff service");field(p,"parentFrame");Integer parentFrame=nullableInt(p,"cutoff parent frame");field(p,"parentOrdinal");long parent=longValue(p,"cutoff parent ordinal");field(p,"depth");int depth=intValue(p,"cutoff depth");field(p,"kind");String kind=text(p,"cutoff service kind");field(p,"state");FrontierServiceState state=enumValue(p,FrontierServiceState.class,"cutoff service state");field(p,"beginFrame");int beginFrame=intValue(p,"cutoff begin frame");field(p,"beginOrdinal");long beginOrdinal=longValue(p,"cutoff begin ordinal");field(p,"endFrame");Integer endFrame=nullableInt(p,"cutoff end frame");field(p,"endOrdinal");Long endOrdinal=nullableLong(p,"cutoff end ordinal");field(p,"chipEvents");List<ChipEvent> chips=chips(p);field(p,"ancestry");ServiceAncestry ancestry=serviceAncestry(p);end(p,"cutoff service");return new CutoffService(parentFrame,parent,depth,kind,state,beginFrame,beginOrdinal,endFrame,endOrdinal,chips,ancestry);}
    private static CutoffNativeDiagnostics cutoffNativeDiagnostics(JsonParser p)throws IOException{if(p.currentToken()==JsonToken.VALUE_NULL)return null;startCurrent(p,"cutoff native diagnostics");field(p,"activeStack");List<FrontierService> active=frontierServices(p);field(p,"pendingDescendants");List<FrontierService> pending=frontierServices(p);field(p,"rawChipInventory");List<FrontierOwnedChip> chips=frontierOwnedChips(p);field(p,"rawSnapshotInventory");List<FrontierOwnedSnapshot> snapshots=frontierOwnedSnapshots(p);field(p,"pendingDeferredServiceBegin");NativeDeferredServiceBegin deferred=nullableNativeDeferredServiceBegin(p);field(p,"armEpoch");long epoch=longValue(p,"native cutoff arm epoch");field(p,"armed");boolean armed=booleanValue(p,"native cutoff armed");field(p,"terminalZ80Digest");String digest=text(p,"native cutoff terminal Z80 digest");end(p,"cutoff native diagnostics");return new CutoffNativeDiagnostics(active,pending,chips,snapshots,deferred,epoch,armed,digest);}
    private static List<FrontierService> frontierServices(JsonParser p) throws IOException { array(p,"frontier services"); List<FrontierService> result=new ArrayList<>(); while(p.nextToken()!=JsonToken.END_ARRAY){if(result.size()==CompleteRunAudioTrace.MAX_CUTOFF_SERVICES)throw invalid("frontier service bound exceeded");startCurrent(p,"frontier service");field(p,"token");long token=longValue(p,"frontier token");field(p,"parentToken");long parent=longValue(p,"frontier parent");field(p,"depth");int depth=intValue(p,"frontier depth");field(p,"kind");String kind=text(p,"frontier kind");field(p,"state");FrontierServiceState state=enumValue(p,FrontierServiceState.class,"frontier service state");field(p,"beginFrame");int frame=intValue(p,"frontier begin frame");field(p,"beginOrdinal");long begin=longValue(p,"frontier begin ordinal");field(p,"beginPc");int pc=intValue(p,"frontier begin PC");field(p,"beginHookToken");int hook=intValue(p,"frontier hook token");field(p,"beginSourceCpu");String beginSource=text(p,"frontier begin source");field(p,"endFrame");Integer endFrame=nullableInt(p,"frontier end frame");field(p,"endOrdinal");Long endOrdinal=nullableLong(p,"frontier end ordinal");field(p,"endPc");Integer endPc=nullableInt(p,"frontier end PC");field(p,"endHookToken");Integer endHook=nullableInt(p,"frontier end hook token");field(p,"snapshots");List<FrontierSnapshot> snapshots=frontierSnapshots(p);field(p,"chipEvents");List<FrontierChipEvent> chips=frontierChips(p);field(p,"currentParentToken");long currentParent=longValue(p,"frontier current parent");field(p,"currentDepth");int currentDepth=intValue(p,"frontier current depth");field(p,"ancestryTransitions");List<NativeAncestryTransition> ancestry=nativeAncestryTransitions(p);field(p,"semanticAncestry");ServiceAncestry semantic=nullableServiceAncestry(p);end(p,"frontier service");result.add(new FrontierService(token,parent,depth,kind,state,frame,begin,pc,hook,beginSource,endFrame,endOrdinal,endPc,endHook,snapshots,chips,currentParent,currentDepth,ancestry,semantic));}return List.copyOf(result); }
    private static List<NativeAncestryTransition> nativeAncestryTransitions(JsonParser p)throws IOException{array(p,"native ancestry transitions");List<NativeAncestryTransition> result=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY){if(result.size()==7)throw invalid("native ancestry-transition bound exceeded");result.add(nativeAncestryTransition(p));}return List.copyOf(result);}
    private static NativeAncestryTransition nativeAncestryTransition(JsonParser p)throws IOException{startCurrent(p,"native ancestry transition");field(p,"coordinate");long coordinate=longValue(p,"native ancestry-transition coordinate");field(p,"frame");int frame=intValue(p,"native ancestry-transition frame");field(p,"ordinal");long ordinal=longValue(p,"native ancestry-transition ordinal");field(p,"previousParentToken");long previousParent=longValue(p,"native ancestry previous parent");field(p,"previousDepth");int previousDepth=intValue(p,"native ancestry previous depth");field(p,"currentParentToken");long currentParent=longValue(p,"native ancestry current parent");field(p,"currentDepth");int currentDepth=intValue(p,"native ancestry current depth");field(p,"hookToken");int hook=intValue(p,"native ancestry hook");field(p,"sourceCpu");String source=text(p,"native ancestry source");field(p,"pc");int pc=intValue(p,"native ancestry PC");end(p,"native ancestry transition");return new NativeAncestryTransition(coordinate,frame,ordinal,previousParent,previousDepth,currentParent,currentDepth,hook,source,pc);}
    private static List<FrontierOwnedAncestryTransition> frontierOwnedAncestryTransitions(JsonParser p)throws IOException{array(p,"native ancestry-transition inventory");List<FrontierOwnedAncestryTransition> result=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY){if(result.size()==CompleteRunAudioTrace.MAX_CUTOFF_SERVICES*7)throw invalid("native ancestry-transition inventory bound exceeded");startCurrent(p,"owned native ancestry transition");field(p,"ownerToken");long owner=longValue(p,"native ancestry-transition owner");field(p,"event");NativeAncestryTransition event=nativeAncestryTransition(p);end(p,"owned native ancestry transition");result.add(new FrontierOwnedAncestryTransition(owner,event));}return List.copyOf(result);}
    private static List<FrontierSnapshot> frontierSnapshots(JsonParser p) throws IOException { array(p,"frontier snapshots");List<FrontierSnapshot> result=new ArrayList<>();long total=0;while(p.nextToken()!=JsonToken.END_ARRAY){FrontierSnapshot snapshot=frontierSnapshot(p);total+=snapshot.bytes().size();if(total>CompleteRunAudioTrace.MAX_CUTOFF_SNAPSHOT_BYTES)throw invalid("frontier snapshot bound exceeded");result.add(snapshot);}return List.copyOf(result); }
    private static FrontierSnapshot frontierSnapshot(JsonParser p) throws IOException {startCurrent(p,"frontier snapshot");field(p,"rangeId");int range=intValue(p,"snapshot range");field(p,"sourceCpu");String source=text(p,"snapshot source");field(p,"pc");int pc=intValue(p,"snapshot PC");field(p,"bytes");List<Integer> bytes=ints(p,"snapshot bytes",CompleteRunAudioTrace.MAX_CUTOFF_SNAPSHOT_BYTES);end(p,"frontier snapshot");return new FrontierSnapshot(range,source,pc,bytes);}
    private static List<FrontierChipEvent> frontierChips(JsonParser p) throws IOException { array(p,"frontier chip events");List<FrontierChipEvent> result=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY){if(result.size()==CompleteRunAudioTrace.MAX_CUTOFF_CHIP_EVENTS)throw invalid("frontier chip-event bound exceeded");result.add(frontierChip(p));}return List.copyOf(result); }
    private static FrontierChipEvent frontierChip(JsonParser p) throws IOException {startCurrent(p,"frontier chip event");field(p,"coordinate");long coordinate=longValue(p,"frontier chip coordinate");field(p,"ordinal");long ordinal=longValue(p,"frontier chip ordinal");field(p,"sourceCpu");String source=text(p,"frontier chip source");field(p,"pc");int pc=intValue(p,"frontier chip PC");field(p,"eventKind");int kind=intValue(p,"frontier chip kind");field(p,"subject");int subject=intValue(p,"frontier chip subject");field(p,"value");int value=intValue(p,"frontier chip value");field(p,"data");boolean data=booleanValue(p,"frontier chip data marker");field(p,"port");Integer port=nullableInt(p,"frontier YM port");field(p,"register");Integer register=nullableInt(p,"frontier YM register");end(p,"frontier chip event");return new FrontierChipEvent(coordinate,ordinal,source,pc,kind,subject,value,data,port,register);}
    private static List<FrontierOwnedChip> frontierOwnedChips(JsonParser p) throws IOException {array(p,"frontier raw chip inventory");List<FrontierOwnedChip> result=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY){if(result.size()==CompleteRunAudioTrace.MAX_CUTOFF_CHIP_EVENTS)throw invalid("frontier raw chip inventory bound exceeded");startCurrent(p,"frontier owned chip");field(p,"ownerToken");long owner=longValue(p,"frontier chip owner");field(p,"serviceIndex");int index=intValue(p,"frontier chip service index");field(p,"event");FrontierChipEvent event=frontierChip(p);end(p,"frontier owned chip");result.add(new FrontierOwnedChip(owner,index,event));}return List.copyOf(result);}
    private static List<FrontierOwnedSnapshot> frontierOwnedSnapshots(JsonParser p) throws IOException {array(p,"frontier raw snapshot inventory");List<FrontierOwnedSnapshot> result=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY){if(result.size()==CompleteRunAudioTrace.MAX_CUTOFF_SERVICES*8L)throw invalid("frontier raw snapshot inventory bound exceeded");startCurrent(p,"frontier owned snapshot");field(p,"ownerToken");long owner=longValue(p,"frontier snapshot owner");field(p,"serviceIndex");int index=intValue(p,"frontier snapshot index");field(p,"snapshot");FrontierSnapshot snapshot=frontierSnapshot(p);end(p,"frontier owned snapshot");result.add(new FrontierOwnedSnapshot(owner,index,snapshot));}return List.copyOf(result);}
    private static Terminal terminal(JsonParser p) throws IOException { startCurrent(p,"terminal"); field(p,"exclusiveEnd"); int end=intValue(p,"terminal end"); field(p,"frameCount"); long frames=longValue(p,"terminal frames"); field(p,"requestCount"); long requests=longValue(p,"terminal requests"); field(p,"serviceCount"); long services=longValue(p,"terminal services"); field(p,"decisionCount"); long decisions=longValue(p,"terminal decisions"); field(p,"ymCount"); long ym=longValue(p,"terminal YM"); field(p,"psgCount"); long psg=longValue(p,"terminal PSG"); field(p,"lifecycleCount"); long lifecycle=longValue(p,"terminal lifecycle"); field(p,"cutoffActiveCount");long active=longValue(p,"terminal cutoff active");field(p,"cutoffPendingCount");long pending=longValue(p,"terminal cutoff pending");field(p,"nativeCapability");NativeCapabilitySummary capability=nativeCapability(p);field(p,"rootDigest"); String digest=text(p,"terminal root digest");field(p,"semanticDigest");String semantic=text(p,"terminal semantic digest"); end(p,"terminal"); return new Terminal(end,frames,requests,services,decisions,ym,psg,lifecycle,active,pending,capability,digest,semantic); }
    private static NativeCapabilitySummary nativeCapability(JsonParser p) throws IOException {if(p.currentToken()==JsonToken.VALUE_NULL)return null;startCurrent(p,"native capability");field(p,"eventCount");long count=longValue(p,"native capability event count");field(p,"maximumFrameOccupancy");int occupancy=intValue(p,"native capability occupancy");field(p,"eventDigest");String eventDigest=text(p,"native event digest");field(p,"vectorDigest");String vectorDigest=text(p,"native capability vector digest");end(p,"native capability");return new NativeCapabilitySummary(count,occupancy,eventDigest,vectorDigest);}

    private static List<Request> requests(JsonParser p) throws IOException { array(p,"requests"); List<Request> result=new ArrayList<>(); while(p.nextToken()!=JsonToken.END_ARRAY){if(result.size()==MAX_FRAME_ITEMS)throw invalid("request bound exceeded");result.add(request(p));} return List.copyOf(result); }
    private static List<Request> nullableRequests(JsonParser p)throws IOException{return p.currentToken()==JsonToken.VALUE_NULL?null:requests(p);}
    private static Request request(JsonParser p) throws IOException { startCurrent(p,"request"); field(p,"ordinal"); long ordinal=longValue(p,"request ordinal"); field(p,"ownerClass"); OwnerClass owner=enumValue(p,OwnerClass.class,"request owner"); field(p,"contentKey"); String key=text(p,"request key"); field(p,"nativeId"); int nativeId=intValue(p,"request ID"); field(p,"queueSource"); String source=text(p,"request source"); field(p,"queueSlot"); Integer slot=nullableInt(p,"request slot"); end(p,"request"); return new Request(ordinal,owner,key,nativeId,source,slot); }
    private static List<DriverService> services(JsonParser p) throws IOException { array(p,"services"); List<DriverService> result=new ArrayList<>(); while(p.nextToken()!=JsonToken.END_ARRAY){if(result.size()==MAX_FRAME_ITEMS)throw invalid("service bound exceeded");result.add(service(p));} return List.copyOf(result); }
    private static List<DriverService> nullableServices(JsonParser p)throws IOException{return p.currentToken()==JsonToken.VALUE_NULL?null:services(p);}
    private static DriverService service(JsonParser p) throws IOException { startCurrent(p,"service"); field(p,"ordinal"); long ordinal=longValue(p,"service ordinal"); field(p,"kind"); String kind=text(p,"service kind"); field(p,"completion");ServiceCompletion completion=enumValue(p,ServiceCompletion.class,"service completion");field(p,"carriedBoundaryOrdinal");Long carried=nullableLong(p,"carried boundary ordinal");field(p,"beginCoordinate");ServiceCoordinate begin=serviceCoordinate(p);field(p,"endCoordinate");ServiceCoordinate endCoordinate=serviceCoordinate(p);field(p,"ancestry");ServiceAncestry ancestry=serviceAncestry(p); end(p,"service"); return new DriverService(ordinal,kind,completion,carried,begin,endCoordinate,ancestry); }
    private static ServiceAncestry serviceAncestry(JsonParser p)throws IOException{startCurrent(p,"service ancestry");field(p,"beginParent");ServiceCoordinate beginParent=serviceCoordinate(p);field(p,"beginDepth");int beginDepth=intValue(p,"service begin depth");field(p,"currentParent");ServiceCoordinate currentParent=serviceCoordinate(p);field(p,"currentDepth");int currentDepth=intValue(p,"service current depth");field(p,"transitions");List<ServiceAncestryTransition> transitions=serviceAncestryTransitions(p);end(p,"service ancestry");return new ServiceAncestry(beginParent,beginDepth,currentParent,currentDepth,transitions);}
    private static ServiceAncestry nullableServiceAncestry(JsonParser p)throws IOException{return p.currentToken()==JsonToken.VALUE_NULL?null:serviceAncestry(p);}
    private static ServiceCoordinate serviceCoordinate(JsonParser p)throws IOException{if(p.currentToken()==JsonToken.VALUE_NULL)return null;startCurrent(p,"service coordinate");field(p,"frame");int frame=intValue(p,"service-coordinate frame");field(p,"ordinal");long ordinal=longValue(p,"service-coordinate ordinal");end(p,"service coordinate");return new ServiceCoordinate(frame,ordinal);}
    private static List<ServiceAncestryTransition> serviceAncestryTransitions(JsonParser p)throws IOException{array(p,"service ancestry transitions");List<ServiceAncestryTransition> result=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY){if(result.size()==7)throw invalid("service ancestry-transition bound exceeded");startCurrent(p,"service ancestry transition");field(p,"previousParent");ServiceCoordinate previous=serviceCoordinate(p);field(p,"previousDepth");int previousDepth=intValue(p,"service ancestry previous depth");field(p,"currentParent");ServiceCoordinate current=serviceCoordinate(p);field(p,"currentDepth");int currentDepth=intValue(p,"service ancestry current depth");field(p,"transitionFrame");int frame=intValue(p,"service ancestry transition frame");field(p,"transitionOrdinal");long ordinal=longValue(p,"service ancestry transition ordinal");end(p,"service ancestry transition");result.add(new ServiceAncestryTransition(previous,previousDepth,current,currentDepth,frame,ordinal));}return List.copyOf(result);}
    private static List<Decision> decisions(JsonParser p) throws IOException { array(p,"decisions"); List<Decision> result=new ArrayList<>(); while(p.nextToken()!=JsonToken.END_ARRAY){if(result.size()==MAX_FRAME_ITEMS)throw invalid("decision bound exceeded");result.add(decision(p));} return List.copyOf(result); }
    private static List<Decision> nullableDecisions(JsonParser p)throws IOException{return p.currentToken()==JsonToken.VALUE_NULL?null:decisions(p);}
    private static Decision decision(JsonParser p) throws IOException { startCurrent(p,"decision"); field(p,"requestOrdinal"); long ordinal=longValue(p,"decision request"); field(p,"resolvedNativeId"); int id=intValue(p,"decision ID"); field(p,"resolvedContentKey"); String key=text(p,"decision key"); field(p,"accepted"); boolean accepted=booleanValue(p,"decision accepted"); field(p,"reason"); String reason=text(p,"decision reason"); field(p,"priorityBefore"); Integer before=nullableInt(p,"priority before"); field(p,"priorityAfter"); Integer after=nullableInt(p,"priority after"); field(p,"requestedRoles"); List<HardwareRole> roles=enums(p,HardwareRole.class,"requested roles"); field(p,"roleDecisions"); List<RoleDecision> roleDecisions=nullableRoleDecisions(p); end(p,"decision"); return new Decision(ordinal,id,key,accepted,reason,before,after,roles,roleDecisions); }
    private static List<RoleDecision> roleDecisions(JsonParser p) throws IOException { array(p,"role decisions"); List<RoleDecision> result=new ArrayList<>(); while(p.nextToken()!=JsonToken.END_ARRAY) { bound(result,"role decision"); startCurrent(p,"role decision"); field(p,"role"); HardwareRole role=enumValue(p,HardwareRole.class,"role decision role"); field(p,"displacedOwner"); OwnerRef displaced=owner(p); field(p,"finalOwner"); OwnerRef finalOwner=owner(p); end(p,"role decision"); result.add(new RoleDecision(role,displaced,finalOwner)); } return List.copyOf(result); }
    private static List<RoleDecision> nullableRoleDecisions(JsonParser p)throws IOException{return p.currentToken()==JsonToken.VALUE_NULL?null:roleDecisions(p);}
    private static List<LifecycleOwnership> lifecycleOwnership(JsonParser p) throws IOException { array(p,"lifecycle ownership transitions"); List<LifecycleOwnership> result=new ArrayList<>(); while(p.nextToken()!=JsonToken.END_ARRAY) { bound(result,"lifecycle ownership transition"); startCurrent(p,"lifecycle ownership transition"); field(p,"role"); HardwareRole role=enumValue(p,HardwareRole.class,"lifecycle ownership role"); field(p,"displacedOwner"); OwnerRef displaced=owner(p); field(p,"finalOwner"); OwnerRef finalOwner=owner(p); end(p,"lifecycle ownership transition"); result.add(new LifecycleOwnership(role,displaced,finalOwner)); } return List.copyOf(result); }
    private static List<LifecycleOwnership> nullableLifecycleOwnership(JsonParser p)throws IOException{return p.currentToken()==JsonToken.VALUE_NULL?null:lifecycleOwnership(p);}
    private static OwnerRef owner(JsonParser p) throws IOException { startCurrent(p,"owner"); field(p,"ownerClass"); OwnerClass owner=enumValue(p,OwnerClass.class,"owner class"); field(p,"contentKey"); String key=text(p,"owner key"); field(p,"nativeId"); int id=intValue(p,"owner ID"); field(p,"origin"); OwnerOrigin origin=enumValue(p,OwnerOrigin.class,"owner origin"); field(p,"originOrdinal"); long ordinal=longValue(p,"owner origin ordinal"); end(p,"owner"); return new OwnerRef(owner,key,id,origin,ordinal); }
    private static List<RoleOwner> roleOwners(JsonParser p) throws IOException { array(p,"role owners"); List<RoleOwner> result=new ArrayList<>(); while(p.nextToken()!=JsonToken.END_ARRAY){bound(result,"role owner");startCurrent(p,"role owner");field(p,"role");HardwareRole role=enumValue(p,HardwareRole.class,"role owner role");field(p,"owner");OwnerRef owner=owner(p);end(p,"role owner");result.add(new RoleOwner(role,owner));}return List.copyOf(result); }
    private static List<RoleOwner> nullableRoleOwners(JsonParser p)throws IOException{return p.currentToken()==JsonToken.VALUE_NULL?null:roleOwners(p);}
    private static NormalizedState state(JsonParser p) throws IOException { startCurrent(p,"state"); field(p,"fields"); List<StateField> fields=stateFields(p); field(p,"roles"); List<RoleState> roles=roles(p); end(p,"state"); return new NormalizedState(fields,roles); }
    private static NormalizedState nullableState(JsonParser p)throws IOException{return p.currentToken()==JsonToken.VALUE_NULL?null:state(p);}
    private static List<StateField> stateFields(JsonParser p) throws IOException { array(p,"state fields"); List<StateField> result=new ArrayList<>(); while(p.nextToken()!=JsonToken.END_ARRAY) { bound(result,"state field"); startCurrent(p,"state field"); field(p,"name"); String name=text(p,"state name"); field(p,"value"); Object value=value(p); end(p,"state field"); result.add(new StateField(name,value)); } return List.copyOf(result); }
    private static List<RoleState> roles(JsonParser p) throws IOException { array(p,"roles"); List<RoleState> result=new ArrayList<>(); while(p.nextToken()!=JsonToken.END_ARRAY) { bound(result,"role state"); startCurrent(p,"role"); field(p,"role"); HardwareRole role=enumValue(p,HardwareRole.class,"role"); field(p,"active"); boolean active=booleanValue(p,"role active"); field(p,"fields"); List<StateField> fields=stateFields(p); end(p,"role"); result.add(new RoleState(role,active,fields)); } return List.copyOf(result); }
    private static List<ChipEvent> chips(JsonParser p) throws IOException { array(p,"chip events"); List<ChipEvent> result=new ArrayList<>(); while(p.nextToken()!=JsonToken.END_ARRAY) { bound(result,"chip event"); startCurrent(p,"chip event"); field(p,"ordinal"); long ordinal=longValue(p,"chip ordinal"); if(p.nextToken()!=JsonToken.FIELD_NAME)throw invalid("chip event type field"); if("port".equals(p.currentName())) { p.nextToken(); int port=intValue(p,"YM port"); field(p,"register"); int register=intValue(p,"YM register"); field(p,"value"); int value=intValue(p,"YM value"); end(p,"YM event"); result.add(new YmWrite(ordinal,port,register,value)); } else if("value".equals(p.currentName())) { p.nextToken(); int value=intValue(p,"PSG value"); end(p,"PSG event"); result.add(new PsgWrite(ordinal,value)); } else throw invalid("unknown chip event type"); } return List.copyOf(result); }
    private static List<ChipEvent> nullableChips(JsonParser p)throws IOException{return p.currentToken()==JsonToken.VALUE_NULL?null:chips(p);}

    private static CompleteRunFixture fixture(JsonParser p) throws IOException { startCurrent(p,"fixture"); field(p,"romSha1"); String sha1=text(p,"ROM SHA1"); field(p,"romCrc32"); String crc=text(p,"ROM CRC"); field(p,"bk2Sha256"); String bk2=text(p,"BK2 hash"); field(p,"bk2RowCount"); long rows=longValue(p,"BK2 rows"); field(p,"runManifestSha256"); String manifest=text(p,"manifest hash"); field(p,"segments"); List<ManifestSegment> segments=segments(p); field(p,"firstFrame"); int first=intValue(p,"first frame"); field(p,"exclusiveEnd"); int end=intValue(p,"end frame"); end(p,"fixture"); return new CompleteRunFixture(sha1,crc,bk2,rows,manifest,segments,first,end); }
    private static List<ManifestSegment> segments(JsonParser p) throws IOException { array(p,"segments"); List<ManifestSegment> result=new ArrayList<>(); while(p.nextToken()!=JsonToken.END_ARRAY) { bound(result,"manifest segment"); startCurrent(p,"segment"); field(p,"id");String id=text(p,"segment ID");field(p,"firstFrame");int first=intValue(p,"segment first");field(p,"exclusiveEnd");int end=intValue(p,"segment end");end(p,"segment");result.add(new ManifestSegment(id,first,end)); } return List.copyOf(result); }
    private static ProducerRuntimeIdentity runtime(JsonParser p) throws IOException { startCurrent(p,"runtime"); field(p,"producerName");String name=text(p,"runtime producer");field(p,"producerVersion");String pv=text(p,"runtime producer version");field(p,"emulatorName");String en=text(p,"runtime emulator");field(p,"emulatorVersion");String ev=text(p,"runtime emulator version");field(p,"coreName");String cn=text(p,"runtime core");field(p,"coreVersion");String cv=text(p,"runtime core version");field(p,"observerAdapter"); ManagedObserverAdapter adapter=enumValue(p,ManagedObserverAdapter.class,"managed observer adapter");field(p,"artifactSha256"); Map<RuntimeArtifact,String> hashes=runtimeHashes(p);end(p,"runtime");return new ProducerRuntimeIdentity(name,pv,en,ev,cn,cv,adapter,hashes); }
    private static Map<RuntimeArtifact,String> runtimeHashes(JsonParser p) throws IOException { startCurrent(p,"runtime hashes"); Map<RuntimeArtifact,String> result=new EnumMap<>(RuntimeArtifact.class); while(p.nextToken()!=JsonToken.END_OBJECT){ if(p.currentToken()!=JsonToken.FIELD_NAME)throw invalid("runtime hash field"); RuntimeArtifact artifact;try{artifact=RuntimeArtifact.valueOf(p.currentName());}catch(IllegalArgumentException failure){throw invalid("unknown runtime artifact",failure);} p.nextToken();String hash=text(p,"runtime hash");if(result.putIfAbsent(artifact,hash)!=null)throw invalid("duplicate runtime artifact: "+artifact); }return Map.copyOf(result); }
    private static ObserverProof proof(JsonParser p) throws IOException { startCurrent(p,"observer proof");field(p,"observerProfile");String profile=text(p,"observer profile");field(p,"callbackSource");String source=text(p,"callback source");field(p,"callbacks");array(p,"callbacks");List<CallbackProof> callbacks=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY){bound(callbacks,"callback proof");startCurrent(p,"callback");field(p,"callback");String name=text(p,"callback");field(p,"observations");long count=longValue(p,"callback count");end(p,"callback");callbacks.add(new CallbackProof(name,count));}end(p,"observer proof");return new ObserverProof(profile,source,callbacks); }
    private static ObserverRuntimeIdentity observerIdentity(JsonParser p) throws IOException { startCurrent(p,"observer runtime identity");field(p,"kind");String kind=text(p,"observer runtime identity kind");if("CALLBACK".equals(kind)){field(p,"id");String id=text(p,"callback observer identity");end(p,"observer runtime identity");return new CallbackObserverIdentity(id);}if(!"BUFFERED_NATIVE".equals(kind))throw invalid("unknown observer runtime identity kind");field(p,"abiName");String abiName=text(p,"native observer ABI name");field(p,"abiVersion");int abiVersion=intValue(p,"native observer ABI version");field(p,"eventSize");int eventSize=intValue(p,"native observer event size");field(p,"configSize");int configSize=intValue(p,"native observer config size");field(p,"kindSize");int kindSize=intValue(p,"native observer kind size");field(p,"hookSize");int hookSize=intValue(p,"native observer hook size");field(p,"rangeSize");int rangeSize=intValue(p,"native observer range size");field(p,"capacity");int capacity=intValue(p,"native observer capacity");field(p,"installationId");String installationId=text(p,"native observer installation ID");field(p,"coreId");String coreId=text(p,"native observer core ID");field(p,"coreBuildId");String buildId=text(p,"native observer BuildID");field(p,"watchMaskSha256");String mask=text(p,"native observer mask SHA-256");field(p,"serviceManifestSha256");String manifest=text(p,"native observer manifest SHA-256");field(p,"enabled");boolean enabled=booleanValue(p,"native observer enabled");field(p,"maximumFrameOccupancy");int occupancy=intValue(p,"native observer occupancy");field(p,"overflowCount");long overflow=longValue(p,"native observer overflow");end(p,"observer runtime identity");return new BufferedNativeObserverIdentity(abiName,abiVersion,eventSize,configSize,kindSize,hookSize,rangeSize,capacity,installationId,coreId,buildId,mask,manifest,enabled,occupancy,overflow); }
    private static ChunkPolicy policy(JsonParser p) throws IOException { startCurrent(p,"chunk policy");field(p,"frameRows");int rows=intValue(p,"chunk rows");field(p,"compression");String compression=text(p,"compression");field(p,"gzipTimestamp");int time=intValue(p,"gzip timestamp");end(p,"chunk policy");return new ChunkPolicy(rows,compression,time); }
    private static StateInventory inventory(JsonParser p) throws IOException { startCurrent(p,"inventory");field(p,"globalFields");List<String> global=texts(p,"global fields");field(p,"activeRoleFields");List<String> active=texts(p,"role fields");end(p,"inventory");return new StateInventory(global,active); }

    private static Object value(JsonParser p) throws IOException { return switch(p.currentToken()){case VALUE_STRING->p.getText();case VALUE_TRUE->true;case VALUE_FALSE->false;case VALUE_NUMBER_INT->integerOrLong(p.getLongValue());case START_ARRAY->{List<Object> list=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY){bound(list,"state array");list.add(value(p));}yield List.copyOf(list);}case START_OBJECT->objectValuesCurrent(p);default->throw invalid("state value must be canonical JSON scalar/container");}; }
    private static Object integerOrLong(long value) { if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) return Integer.valueOf((int) value); return Long.valueOf(value); }
    private static Map<String,Object> objectValues(JsonParser p,String label)throws IOException{startCurrent(p,label);return objectValuesBody(p,label);} private static Map<String,Object> objectValuesCurrent(JsonParser p)throws IOException{return objectValuesBody(p,"object");} private static Map<String,Object> objectValuesBody(JsonParser p,String label)throws IOException{Map<String,Object> map=new LinkedHashMap<>();while(p.nextToken()!=JsonToken.END_OBJECT){if(map.size()==MAX_FRAME_ITEMS)throw invalid(label+" bound exceeded");if(p.currentToken()!=JsonToken.FIELD_NAME||map.containsKey(p.currentName()))throw invalid(label+" key");String key=p.currentName();p.nextToken();map.put(key,value(p));}return Map.copyOf(map);}
    private static List<String> texts(JsonParser p,String label)throws IOException{array(p,label);List<String> list=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY){bound(list,label);list.add(text(p,label));}return List.copyOf(list);} private static <E extends Enum<E>> List<E> enums(JsonParser p,Class<E> type,String label)throws IOException{array(p,label);List<E> list=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY){bound(list,label);list.add(enumValue(p,type,label));}return List.copyOf(list);} private static <E extends Enum<E>> E enumValue(JsonParser p,Class<E> type,String label)throws IOException{try{return Enum.valueOf(type,text(p,label));}catch(IllegalArgumentException failure){throw invalid("unknown "+label,failure);}}
    private static void bound(List<?> values,String label){if(values.size()==MAX_FRAME_ITEMS)throw invalid(label+" bound exceeded");}
    private static String type(CompleteRunAudioTrace.Record record){if(record instanceof Baseline)return"baseline";if(record instanceof Frame)return"frame";if(record instanceof Lifecycle)return"lifecycle";if(record instanceof CutoffFrontier)return"cutoff_frontier";if(record instanceof Terminal)return"terminal";throw invalid("unknown record class");}
    private static void start(JsonParser p,String label)throws IOException{if(p.nextToken()!=JsonToken.START_OBJECT)throw invalid(label+" must start object");} private static void startCurrent(JsonParser p,String label){if(p.currentToken()!=JsonToken.START_OBJECT)throw invalid(label+" must be object");} private static void field(JsonParser p,String expected)throws IOException{if(p.nextToken()!=JsonToken.FIELD_NAME||!expected.equals(p.currentName()))throw invalid("expected field: "+expected);if(p.nextToken()==null)throw invalid("missing field value: "+expected);} private static void end(JsonParser p,String label)throws IOException{if(p.nextToken()!=JsonToken.END_OBJECT)throw invalid(label+" contains unknown or missing fields");} private static void eof(JsonParser p,String label)throws IOException{if(p.nextToken()!=null)throw invalid("trailing JSON after "+label);} private static void array(JsonParser p,String label){if(p.currentToken()!=JsonToken.START_ARRAY)throw invalid(label+" must be array");}
    private static String text(JsonParser p,String label)throws IOException{if(p.currentToken()!=JsonToken.VALUE_STRING)throw invalid(label+" must be string");return p.getText();} private static String nullableText(JsonParser p,String label)throws IOException{if(p.currentToken()==JsonToken.VALUE_NULL)return null;return text(p,label);} private static boolean booleanValue(JsonParser p,String label)throws IOException{if(p.currentToken()!=JsonToken.VALUE_TRUE&&p.currentToken()!=JsonToken.VALUE_FALSE)throw invalid(label+" must be boolean");return p.getBooleanValue();} private static Boolean nullableBoolean(JsonParser p,String label)throws IOException{return p.currentToken()==JsonToken.VALUE_NULL?null:booleanValue(p,label);} private static int intValue(JsonParser p,String label)throws IOException{if(p.currentToken()!=JsonToken.VALUE_NUMBER_INT||!p.canReadTypeId()&&(!p.hasCurrentToken()))throw invalid(label+" must be integer");try{return p.getIntValue();}catch(Exception failure){throw invalid(label+" out of int range",failure);}} private static long longValue(JsonParser p,String label)throws IOException{if(p.currentToken()!=JsonToken.VALUE_NUMBER_INT)throw invalid(label+" must be integer");return p.getLongValue();} private static Long nullableLong(JsonParser p,String label)throws IOException{return p.currentToken()==JsonToken.VALUE_NULL?null:longValue(p,label);} private static Integer nullableInt(JsonParser p,String label)throws IOException{return p.currentToken()==JsonToken.VALUE_NULL?null:intValue(p,label);} private static List<Integer> ints(JsonParser p,String label,long maximum)throws IOException{array(p,label);List<Integer> list=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY){if(list.size()>=maximum)throw invalid(label+" bound exceeded");list.add(intValue(p,label));}return List.copyOf(list);} static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);} static IllegalArgumentException invalid(String message,Throwable cause){return new IllegalArgumentException(message,cause);}
}
