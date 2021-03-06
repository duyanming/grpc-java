/*
 * Copyright 2019 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.xds.XdsLbPolicies.EDS_POLICY_NAME;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.BAD_CLIENT_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.BAD_CLIENT_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CA_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CLIENT_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CLIENT_PEM_FILE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.xds.CdsLoadBalancerProvider.CdsConfig;
import io.grpc.xds.EdsLoadBalancerProvider.EdsConfig;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.XdsClient.ClusterUpdate;
import io.grpc.xds.XdsClient.ClusterWatcher;
import io.grpc.xds.XdsClient.EndpointUpdate;
import io.grpc.xds.XdsClient.EndpointWatcher;
import io.grpc.xds.XdsClient.RefCountedXdsClientObjectPool;
import io.grpc.xds.XdsClient.XdsClientFactory;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.SslContextProvider;
import io.grpc.xds.internal.sds.TlsContextManager;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link CdsLoadBalancer}.
 */
@RunWith(JUnit4.class)
public class CdsLoadBalancerTest {

  private final RefCountedXdsClientObjectPool xdsClientPool = new RefCountedXdsClientObjectPool(
      new XdsClientFactory() {
        @Override
        XdsClient createXdsClient() {
          xdsClient = mock(XdsClient.class);
          return xdsClient;
        }
      }
  );

  private final LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
  private final LoadBalancerProvider fakeEdsLoadBlancerProvider = new LoadBalancerProvider() {
    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 5;
    }

    @Override
    public String getPolicyName() {
      return EDS_POLICY_NAME;
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      edsLbHelpers.add(helper);
      LoadBalancer edsLoadBalancer = mock(LoadBalancer.class);
      edsLoadBalancers.add(edsLoadBalancer);
      return edsLoadBalancer;
    }
  };

  private final LoadBalancerProvider fakeRoundRobinLbProvider = new LoadBalancerProvider() {
    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 5;
    }

    @Override
    public String getPolicyName() {
      return "round_robin";
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return mock(LoadBalancer.class);
    }

    @Override
    public ConfigOrError parseLoadBalancingPolicyConfig(
        Map<String, ?> rawLoadBalancingPolicyConfig) {
      return ConfigOrError.fromConfig("fake round robin config");
    }
  };

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  private final FakeClock fakeClock = new FakeClock();
  private final Deque<LoadBalancer> edsLoadBalancers = new ArrayDeque<>();
  private final Deque<Helper> edsLbHelpers = new ArrayDeque<>();

  @Mock
  private Helper helper;

  private LoadBalancer cdsLoadBalancer;
  private XdsClient xdsClient;

  @Mock
  private TlsContextManager mockTlsContextManager;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);

    doReturn(syncContext).when(helper).getSynchronizationContext();
    doReturn(fakeClock.getScheduledExecutorService()).when(helper).getScheduledExecutorService();
    lbRegistry.register(fakeEdsLoadBlancerProvider);
    lbRegistry.register(fakeRoundRobinLbProvider);
    cdsLoadBalancer = new CdsLoadBalancer(helper, lbRegistry, mockTlsContextManager);
  }

  @Test
  public void canHandleEmptyAddressListFromNameResolution() {
    assertThat(cdsLoadBalancer.canHandleEmptyAddressListFromNameResolution()).isTrue();
  }

  @Test
  public void handleResolutionErrorBeforeOrAfterCdsWorking() {
    ResolvedAddresses resolvedAddresses1 = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(Attributes.newBuilder()
            .set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
            .build())
        .setLoadBalancingPolicyConfig(new CdsConfig("foo.googleapis.com"))
        .build();
    cdsLoadBalancer.handleResolvedAddresses(resolvedAddresses1);
    ArgumentCaptor<ClusterWatcher> clusterWatcherCaptor1 = ArgumentCaptor.forClass(null);
    verify(xdsClient).watchClusterData(eq("foo.googleapis.com"), clusterWatcherCaptor1.capture());
    ClusterWatcher clusterWatcher1 = clusterWatcherCaptor1.getValue();

    // handleResolutionError() before receiving any CDS response.
    cdsLoadBalancer.handleNameResolutionError(Status.DATA_LOSS.withDescription("fake status"));
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));

    // CDS response received.
    clusterWatcher1.onClusterChanged(
        ClusterUpdate.newBuilder()
            .setClusterName("foo.googleapis.com")
            .setEdsServiceName("edsServiceFoo.googleapis.com")
            .setLbPolicy("round_robin")
            .build());
    verify(helper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));

    // handleResolutionError() after receiving CDS response.
    cdsLoadBalancer.handleNameResolutionError(Status.DATA_LOSS.withDescription("fake status"));
    // No more TRANSIENT_FAILURE.
    verify(helper, times(1)).updateBalancingState(
        eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));
  }

  @Test
  public void handleCdsConfigUpdate() {
    assertThat(xdsClient).isNull();
    ResolvedAddresses resolvedAddresses1 = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(Attributes.newBuilder()
            .set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
            .build())
        .setLoadBalancingPolicyConfig(new CdsConfig("foo.googleapis.com"))
        .build();
    cdsLoadBalancer.handleResolvedAddresses(resolvedAddresses1);

    ArgumentCaptor<ClusterWatcher> clusterWatcherCaptor1 = ArgumentCaptor.forClass(null);
    verify(xdsClient).watchClusterData(eq("foo.googleapis.com"), clusterWatcherCaptor1.capture());

    ClusterWatcher clusterWatcher1 = clusterWatcherCaptor1.getValue();
    clusterWatcher1.onClusterChanged(
        ClusterUpdate.newBuilder()
            .setClusterName("foo.googleapis.com")
            .setEdsServiceName("edsServiceFoo.googleapis.com")
            .setLbPolicy("round_robin")
            .build());

    assertThat(edsLbHelpers).hasSize(1);
    assertThat(edsLoadBalancers).hasSize(1);
    Helper edsLbHelper1 = edsLbHelpers.poll();
    LoadBalancer edsLoadBalancer1 = edsLoadBalancers.poll();
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor1 = ArgumentCaptor.forClass(null);
    verify(edsLoadBalancer1).handleResolvedAddresses(resolvedAddressesCaptor1.capture());
    PolicySelection roundRobinPolicy = new PolicySelection(
        fakeRoundRobinLbProvider, new HashMap<String, Object>(), "fake round robin config");
    EdsConfig expectedEdsConfig = new EdsConfig(
        "foo.googleapis.com",
        "edsServiceFoo.googleapis.com",
        null,
        roundRobinPolicy);
    ResolvedAddresses resolvedAddressesFoo = resolvedAddressesCaptor1.getValue();
    assertThat(resolvedAddressesFoo.getLoadBalancingPolicyConfig()).isEqualTo(expectedEdsConfig);
    assertThat(resolvedAddressesFoo.getAttributes().get(XdsAttributes.XDS_CLIENT_POOL))
        .isSameInstanceAs(xdsClientPool);

    SubchannelPicker picker1 = mock(SubchannelPicker.class);
    edsLbHelper1.updateBalancingState(ConnectivityState.READY, picker1);
    verify(helper).updateBalancingState(ConnectivityState.READY, picker1);

    ResolvedAddresses resolvedAddresses2 = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(Attributes.newBuilder()
            .set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
            .build())
        .setLoadBalancingPolicyConfig(new CdsConfig("bar.googleapis.com"))
        .build();
    cdsLoadBalancer.handleResolvedAddresses(resolvedAddresses2);

    ArgumentCaptor<ClusterWatcher> clusterWatcherCaptor2 = ArgumentCaptor.forClass(null);
    verify(xdsClient).watchClusterData(eq("bar.googleapis.com"), clusterWatcherCaptor2.capture());

    ClusterWatcher clusterWatcher2 = clusterWatcherCaptor2.getValue();
    clusterWatcher2.onClusterChanged(
        ClusterUpdate.newBuilder()
            .setClusterName("bar.googleapis.com")
            .setEdsServiceName("edsServiceBar.googleapis.com")
            .setLbPolicy("round_robin")
            .setLrsServerName("lrsBar.googleapis.com")
            .build());

    assertThat(edsLbHelpers).hasSize(1);
    assertThat(edsLoadBalancers).hasSize(1);
    Helper edsLbHelper2 = edsLbHelpers.poll();
    LoadBalancer edsLoadBalancer2 = edsLoadBalancers.poll();
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor2 = ArgumentCaptor.forClass(null);
    verify(edsLoadBalancer2).handleResolvedAddresses(resolvedAddressesCaptor2.capture());
    expectedEdsConfig = new EdsConfig(
        "bar.googleapis.com",
        "edsServiceBar.googleapis.com",
        "lrsBar.googleapis.com",
        roundRobinPolicy);
    ResolvedAddresses resolvedAddressesBar = resolvedAddressesCaptor2.getValue();
    assertThat(resolvedAddressesBar.getLoadBalancingPolicyConfig()).isEqualTo(expectedEdsConfig);
    assertThat(resolvedAddressesBar.getAttributes().get(XdsAttributes.XDS_CLIENT_POOL))
        .isSameInstanceAs(xdsClientPool);

    SubchannelPicker picker2 = mock(SubchannelPicker.class);
    edsLbHelper2.updateBalancingState(ConnectivityState.CONNECTING, picker2);
    verify(helper, never()).updateBalancingState(ConnectivityState.CONNECTING, picker2);
    verify(edsLoadBalancer1, never()).shutdown();

    picker2 = mock(SubchannelPicker.class);
    edsLbHelper2.updateBalancingState(ConnectivityState.READY, picker2);
    verify(helper).updateBalancingState(ConnectivityState.READY, picker2);
    verify(edsLoadBalancer1).shutdown();
    verify(xdsClient).cancelClusterDataWatch("foo.googleapis.com", clusterWatcher1);

    clusterWatcher2.onClusterChanged(
        ClusterUpdate.newBuilder()
            .setClusterName("bar.googleapis.com")
            .setEdsServiceName("edsServiceBar2.googleapis.com")
            .setLbPolicy("round_robin")
            .build());
    verify(edsLoadBalancer2, times(2)).handleResolvedAddresses(resolvedAddressesCaptor2.capture());
    expectedEdsConfig = new EdsConfig(
        "bar.googleapis.com",
        "edsServiceBar2.googleapis.com",
        null,
        roundRobinPolicy);
    ResolvedAddresses resolvedAddressesBar2 = resolvedAddressesCaptor2.getValue();
    assertThat(resolvedAddressesBar2.getLoadBalancingPolicyConfig()).isEqualTo(expectedEdsConfig);

    cdsLoadBalancer.shutdown();
    verify(edsLoadBalancer2).shutdown();
    verify(xdsClient).cancelClusterDataWatch("bar.googleapis.com", clusterWatcher2);
    assertThat(xdsClientPool.xdsClient).isNull();
  }

  @Ignore
  @Test
  public void handleCdsConfigUpdate_withUpstreamTlsContext()  {
    assertThat(xdsClient).isNull();
    ResolvedAddresses resolvedAddresses1 =
         ResolvedAddresses.newBuilder()
             .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
             .setAttributes(
                 Attributes.newBuilder()
                     .set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
                     .build())
             .setLoadBalancingPolicyConfig(new CdsConfig("foo.googleapis.com"))
             .build();
    cdsLoadBalancer.handleResolvedAddresses(resolvedAddresses1);

    ArgumentCaptor<ClusterWatcher> clusterWatcherCaptor1 = ArgumentCaptor.forClass(null);
    verify(xdsClient).watchClusterData(eq("foo.googleapis.com"), clusterWatcherCaptor1.capture());

    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
            CLIENT_KEY_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);

    SslContextProvider mockSslContextProvider = mock(SslContextProvider.class);
    doReturn(upstreamTlsContext).when(mockSslContextProvider).getUpstreamTlsContext();
    doReturn(mockSslContextProvider).when(mockTlsContextManager)
        .findOrCreateClientSslContextProvider(same(upstreamTlsContext));

    ClusterWatcher clusterWatcher1 = clusterWatcherCaptor1.getValue();
    clusterWatcher1.onClusterChanged(
        ClusterUpdate.newBuilder()
            .setClusterName("foo.googleapis.com")
            .setEdsServiceName("edsServiceFoo.googleapis.com")
            .setLbPolicy("round_robin")
            .setUpstreamTlsContext(upstreamTlsContext)
            .build());

    assertThat(edsLbHelpers).hasSize(1);
    assertThat(edsLoadBalancers).hasSize(1);
    verify(mockTlsContextManager, never())
        .releaseClientSslContextProvider(any(SslContextProvider.class));
    Helper edsLbHelper1 = edsLbHelpers.poll();

    ArrayList<EquivalentAddressGroup> eagList = new ArrayList<>();
    eagList.add(new EquivalentAddressGroup(new InetSocketAddress("foo.com", 8080)));
    eagList.add(new EquivalentAddressGroup(InetSocketAddress.createUnresolved("localhost", 8081),
        Attributes.newBuilder().set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool).build()));
    LoadBalancer.CreateSubchannelArgs createSubchannelArgs =
        LoadBalancer.CreateSubchannelArgs.newBuilder()
            .setAddresses(eagList)
            .build();
    ArgumentCaptor<LoadBalancer.CreateSubchannelArgs> createSubchannelArgsCaptor1 =
        ArgumentCaptor.forClass(null);
    verify(helper, never())
        .createSubchannel(any(LoadBalancer.CreateSubchannelArgs.class));
    edsLbHelper1.createSubchannel(createSubchannelArgs);
    verifyUpstreamTlsContextAttribute(upstreamTlsContext,
        createSubchannelArgsCaptor1);

    // update with same upstreamTlsContext
    reset(mockTlsContextManager);
    clusterWatcher1.onClusterChanged(
        ClusterUpdate.newBuilder()
            .setClusterName("bar.googleapis.com")
            .setEdsServiceName("eds1ServiceFoo.googleapis.com")
            .setLbPolicy("round_robin")
            .setUpstreamTlsContext(upstreamTlsContext)
            .build());

    verify(mockTlsContextManager, never())
        .releaseClientSslContextProvider(any(SslContextProvider.class));
    verify(mockTlsContextManager, never()).findOrCreateClientSslContextProvider(
        any(UpstreamTlsContext.class));

    // update with different upstreamTlsContext
    reset(mockTlsContextManager);
    reset(helper);
    UpstreamTlsContext upstreamTlsContext1 =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
            BAD_CLIENT_KEY_FILE, BAD_CLIENT_PEM_FILE, CA_PEM_FILE);
    SslContextProvider mockSslContextProvider1 = mock(SslContextProvider.class);
    doReturn(upstreamTlsContext1).when(mockSslContextProvider1).getUpstreamTlsContext();
    doReturn(mockSslContextProvider1).when(mockTlsContextManager)
        .findOrCreateClientSslContextProvider(same(upstreamTlsContext1));
    clusterWatcher1.onClusterChanged(
        ClusterUpdate.newBuilder()
            .setClusterName("bar.googleapis.com")
            .setEdsServiceName("eds1ServiceFoo.googleapis.com")
            .setLbPolicy("round_robin")
            .setUpstreamTlsContext(upstreamTlsContext1)
            .build());

    verify(mockTlsContextManager).releaseClientSslContextProvider(same(mockSslContextProvider));
    verify(mockTlsContextManager).findOrCreateClientSslContextProvider(same(upstreamTlsContext1));
    ArgumentCaptor<LoadBalancer.CreateSubchannelArgs> createSubchannelArgsCaptor2 =
        ArgumentCaptor.forClass(null);
    edsLbHelper1.createSubchannel(createSubchannelArgs);
    verifyUpstreamTlsContextAttribute(upstreamTlsContext1,
        createSubchannelArgsCaptor2);

    // update with null
    reset(mockTlsContextManager);
    reset(helper);
    clusterWatcher1.onClusterChanged(
        ClusterUpdate.newBuilder()
            .setClusterName("bar.googleapis.com")
            .setEdsServiceName("eds1ServiceFoo.googleapis.com")
            .setLbPolicy("round_robin")
            .setUpstreamTlsContext(null)
            .build());
    verify(mockTlsContextManager).releaseClientSslContextProvider(same(mockSslContextProvider1));
    verify(mockTlsContextManager, never()).findOrCreateClientSslContextProvider(
        any(UpstreamTlsContext.class));
    ArgumentCaptor<LoadBalancer.CreateSubchannelArgs> createSubchannelArgsCaptor3 =
        ArgumentCaptor.forClass(null);
    edsLbHelper1.createSubchannel(createSubchannelArgs);
    verifyUpstreamTlsContextAttribute(null,
        createSubchannelArgsCaptor3);

    LoadBalancer edsLoadBalancer1 = edsLoadBalancers.poll();

    cdsLoadBalancer.shutdown();
    verify(edsLoadBalancer1).shutdown();
    verify(xdsClient).cancelClusterDataWatch("foo.googleapis.com", clusterWatcher1);
    assertThat(xdsClientPool.xdsClient).isNull();
  }

  private void verifyUpstreamTlsContextAttribute(
      UpstreamTlsContext upstreamTlsContext,
      ArgumentCaptor<CreateSubchannelArgs> createSubchannelArgsCaptor1) {
    verify(helper, times(1)).createSubchannel(createSubchannelArgsCaptor1.capture());
    CreateSubchannelArgs capturedValue = createSubchannelArgsCaptor1.getValue();
    List<EquivalentAddressGroup> capturedEagList = capturedValue.getAddresses();
    assertThat(capturedEagList.size()).isEqualTo(2);
    EquivalentAddressGroup capturedEag = capturedEagList.get(0);
    UpstreamTlsContext capturedUpstreamTlsContext =
        capturedEag.getAttributes().get(XdsAttributes.ATTR_UPSTREAM_TLS_CONTEXT);
    assertThat(capturedUpstreamTlsContext).isSameInstanceAs(upstreamTlsContext);
    capturedEag = capturedEagList.get(1);
    capturedUpstreamTlsContext =
        capturedEag.getAttributes().get(XdsAttributes.ATTR_UPSTREAM_TLS_CONTEXT);
    assertThat(capturedUpstreamTlsContext).isSameInstanceAs(upstreamTlsContext);
    assertThat(capturedEag.getAttributes().get(XdsAttributes.XDS_CLIENT_POOL))
        .isSameInstanceAs(xdsClientPool);
  }

  @Test
  public void clusterWatcher_resourceNotExist() {
    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(Attributes.newBuilder()
            .set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
            .build())
        .setLoadBalancingPolicyConfig(new CdsConfig("foo.googleapis.com"))
        .build();
    cdsLoadBalancer.handleResolvedAddresses(resolvedAddresses);

    ArgumentCaptor<ClusterWatcher> clusterWatcherCaptor = ArgumentCaptor.forClass(null);
    verify(xdsClient).watchClusterData(eq("foo.googleapis.com"), clusterWatcherCaptor.capture());

    ClusterWatcher clusterWatcher = clusterWatcherCaptor.getValue();
    ArgumentCaptor<SubchannelPicker> pickerCaptor = ArgumentCaptor.forClass(null);
    clusterWatcher.onResourceDoesNotExist("foo.googleapis.com");
    assertThat(edsLoadBalancers).isEmpty();
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription())
        .isEqualTo("Resource foo.googleapis.com is unavailable");
  }

  @Test
  public void clusterWatcher_resourceRemoved() {
    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(Attributes.newBuilder()
            .set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
            .build())
        .setLoadBalancingPolicyConfig(new CdsConfig("foo.googleapis.com"))
        .build();
    cdsLoadBalancer.handleResolvedAddresses(resolvedAddresses);

    ArgumentCaptor<ClusterWatcher> clusterWatcherCaptor = ArgumentCaptor.forClass(null);
    verify(xdsClient).watchClusterData(eq("foo.googleapis.com"), clusterWatcherCaptor.capture());

    ClusterWatcher clusterWatcher = clusterWatcherCaptor.getValue();
    ArgumentCaptor<SubchannelPicker> pickerCaptor = ArgumentCaptor.forClass(null);
    clusterWatcher.onClusterChanged(
        ClusterUpdate.newBuilder()
            .setClusterName("foo.googleapis.com")
            .setEdsServiceName("edsServiceFoo.googleapis.com")
            .setLbPolicy("round_robin")
            .build());
    assertThat(edsLoadBalancers).hasSize(1);
    assertThat(edsLbHelpers).hasSize(1);
    LoadBalancer edsLoadBalancer = edsLoadBalancers.poll();
    Helper edsHelper = edsLbHelpers.poll();
    SubchannelPicker subchannelPicker = mock(SubchannelPicker.class);
    edsHelper.updateBalancingState(READY, subchannelPicker);
    verify(helper).updateBalancingState(eq(READY), same(subchannelPicker));

    clusterWatcher.onResourceDoesNotExist("foo.googleapis.com");
    verify(edsLoadBalancer).shutdown();
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription())
        .isEqualTo("Resource foo.googleapis.com is unavailable");
  }

  @Test
  public void clusterWatcher_onErrorCalledBeforeAndAfterOnClusterChanged() {
    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(Attributes.newBuilder()
            .set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
            .build())
        .setLoadBalancingPolicyConfig(new CdsConfig("foo.googleapis.com"))
        .build();
    cdsLoadBalancer.handleResolvedAddresses(resolvedAddresses);

    ArgumentCaptor<ClusterWatcher> clusterWatcherCaptor = ArgumentCaptor.forClass(null);
    verify(xdsClient).watchClusterData(eq("foo.googleapis.com"), clusterWatcherCaptor.capture());

    ClusterWatcher clusterWatcher = clusterWatcherCaptor.getValue();

    // Call onError() before onClusterChanged() ever called.
    clusterWatcher.onError(Status.DATA_LOSS.withDescription("fake status"));
    assertThat(edsLoadBalancers).isEmpty();
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));

    clusterWatcher.onClusterChanged(
        ClusterUpdate.newBuilder()
            .setClusterName("foo.googleapis.com")
            .setEdsServiceName("edsServiceFoo.googleapis.com")
            .setLbPolicy("round_robin")
            .build());

    assertThat(edsLbHelpers).hasSize(1);
    assertThat(edsLoadBalancers).hasSize(1);
    Helper edsLbHelper = edsLbHelpers.poll();
    LoadBalancer edsLoadBalancer = edsLoadBalancers.poll();
    verify(edsLoadBalancer).handleResolvedAddresses(any(ResolvedAddresses.class));
    SubchannelPicker picker = mock(SubchannelPicker.class);

    edsLbHelper.updateBalancingState(ConnectivityState.READY, picker);
    verify(helper).updateBalancingState(ConnectivityState.READY, picker);

    // Call onError() after onClusterChanged().
    clusterWatcher.onError(Status.DATA_LOSS.withDescription("fake status"));
    // Verify no more TRANSIENT_FAILURE.
    verify(helper, times(1))
        .updateBalancingState(eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));
  }

  @Test
  public void cdsBalancerIntegrateWithEdsBalancer() {
    lbRegistry.deregister(fakeEdsLoadBlancerProvider);
    lbRegistry.register(new EdsLoadBalancerProvider());

    ResolvedAddresses resolvedAddresses1 = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(Attributes.newBuilder()
            .set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
            .build())
        .setLoadBalancingPolicyConfig(new CdsConfig("foo.googleapis.com"))
        .build();
    cdsLoadBalancer.handleResolvedAddresses(resolvedAddresses1);
    ArgumentCaptor<ClusterWatcher> clusterWatcherCaptor = ArgumentCaptor.forClass(null);
    verify(xdsClient).watchClusterData(eq("foo.googleapis.com"), clusterWatcherCaptor.capture());
    ClusterWatcher clusterWatcher = clusterWatcherCaptor.getValue();
    clusterWatcher.onClusterChanged(
        ClusterUpdate.newBuilder()
            .setClusterName("foo.googleapis.com")
            .setEdsServiceName("edsServiceFoo.googleapis.com")
            .setLbPolicy("round_robin")
            .build());

    ArgumentCaptor<EndpointWatcher> endpointWatcherCaptor = ArgumentCaptor.forClass(null);
    verify(xdsClient).watchEndpointData(
        eq("edsServiceFoo.googleapis.com"), endpointWatcherCaptor.capture());
    EndpointWatcher endpointWatcher = endpointWatcherCaptor.getValue();

    verify(helper, never()).updateBalancingState(
        eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));
    // Update endpoints with all backends unhealthy, the EDS will update channel state to
    // TRANSIENT_FAILURE.
    // Not able to test with healthy endpoints because the real EDS balancer is using real
    // round-robin balancer to balance endpoints.
    endpointWatcher.onEndpointChanged(EndpointUpdate.newBuilder()
        .setClusterName("edsServiceFoo.googleapis.com")
        .addLocalityLbEndpoints(
            new EnvoyProtoData.Locality("region", "zone", "subzone"),
            new EnvoyProtoData.LocalityLbEndpoints(
                // All unhealthy.
                ImmutableList.of(new EnvoyProtoData.LbEndpoint("127.0.0.1", 8080, 1, false)), 1, 0))
        .build());
    verify(helper, atLeastOnce()).updateBalancingState(
        eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));

    cdsLoadBalancer.shutdown();
  }
}
