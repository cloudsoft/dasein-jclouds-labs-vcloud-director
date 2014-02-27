/**
 * Copyright (C) 2009-2012 enStratus Networks Inc
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */

package org.dasein.cloud.jclouds.vcloud.director.compute;

import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;

import org.apache.log4j.Logger;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.Tag;
import org.dasein.cloud.compute.Architecture;
import org.dasein.cloud.compute.Platform;
import org.dasein.cloud.compute.VirtualMachine;
import org.dasein.cloud.compute.VirtualMachineProduct;
import org.dasein.cloud.compute.VirtualMachineSupport;
import org.dasein.cloud.compute.VmState;
import org.dasein.cloud.compute.VmStatistics;
import org.dasein.cloud.dc.DataCenter;
import org.dasein.cloud.identity.ServiceAction;
import org.dasein.cloud.jclouds.vcloud.director.VCloudDirector;
import org.dasein.cloud.network.VLAN;
import org.jclouds.dmtf.cim.ResourceAllocationSettingData;
import org.jclouds.dmtf.cim.ResourceAllocationSettingData.ResourceType;
import org.jclouds.dmtf.ovf.SectionType;
import org.jclouds.rest.AuthorizationException;
import org.jclouds.rest.RestContext;
import org.jclouds.vcloud.director.v1_5.VCloudDirectorMediaType;
import org.jclouds.vcloud.director.v1_5.admin.VCloudDirectorAdminAsyncApi;
import org.jclouds.vcloud.director.v1_5.admin.VCloudDirectorAdminApi;
import org.jclouds.vcloud.director.v1_5.domain.AbstractVAppType;
import org.jclouds.vcloud.director.v1_5.domain.Link;
import org.jclouds.vcloud.director.v1_5.domain.Reference;
import org.jclouds.vcloud.director.v1_5.domain.ResourceEntity.Status;
import org.jclouds.vcloud.director.v1_5.domain.Task;
import org.jclouds.vcloud.director.v1_5.domain.VApp;
import org.jclouds.vcloud.director.v1_5.domain.VAppTemplate;
import org.jclouds.vcloud.director.v1_5.domain.Vdc;
import org.jclouds.vcloud.director.v1_5.domain.Vm;
import org.jclouds.vcloud.director.v1_5.domain.dmtf.RasdItem;
import org.jclouds.vcloud.director.v1_5.domain.network.NetworkConnection;
import org.jclouds.vcloud.director.v1_5.domain.network.NetworkConnection.IpAddressAllocationMode;
import org.jclouds.vcloud.director.v1_5.domain.params.CloneVAppParams;
import org.jclouds.vcloud.director.v1_5.domain.params.DeployVAppParams;
import org.jclouds.vcloud.director.v1_5.domain.params.InstantiateVAppTemplateParams;
import org.jclouds.vcloud.director.v1_5.domain.params.InstantiationParams;
import org.jclouds.vcloud.director.v1_5.domain.params.UndeployVAppParams;
import org.jclouds.vcloud.director.v1_5.domain.section.GuestCustomizationSection;
import org.jclouds.vcloud.director.v1_5.domain.section.NetworkConnectionSection;
import org.jclouds.vcloud.director.v1_5.domain.section.VirtualHardwareSection;
import org.jclouds.vcloud.director.v1_5.predicates.LinkPredicates;
import org.jclouds.vcloud.director.v1_5.predicates.ReferencePredicates;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

public class VmSupport implements VirtualMachineSupport {
    static private final Logger logger = Logger.getLogger(VirtualMachineSupport.class);
    
    private VCloudDirector provider;
    
    VmSupport(VCloudDirector provider) { this.provider = provider; }
    
    @Override
    public void boot(String vmId) throws InternalException, CloudException {
        RestContext<VCloudDirectorAdminApi, VCloudDirectorAdminAsyncApi> ctx = provider.getCloudClient();
        
        try {
            try {
                Task task = ctx.getApi().getVAppApi().powerOn(provider.toHref(ctx, vmId));
                
                provider.waitForTask(task);
            }
            catch( RuntimeException e ) {
                logger.error("Error booting " + vmId + ": " + e.getMessage());
                if( logger.isDebugEnabled() ) {
                    e.printStackTrace();
                }
                throw new CloudException(e);
            }
        }
        finally {
            ctx.close();
        }
    }

    @Override
    public VirtualMachine clone(String vmId, String intoDcId, String name, String description, boolean powerOn, String... firewallIds) throws InternalException, CloudException {
        RestContext<VCloudDirectorAdminApi, VCloudDirectorAdminAsyncApi> ctx = provider.getCloudClient();
        
        try {
            try {
                CloneVAppParams.Builder<?> options = CloneVAppParams.builder().description(description).name(name);
                
                if( powerOn ) {
                    options.powerOn();
                }
                // note this says vmId, but the copy operation is on the vApp. you might want to rename this variable accordingly
                URI vAppUri = null;
                VApp clone = ctx.getApi().getVdcApi().cloneVApp(vAppUri, options.build());

                Task task = Iterables.find(clone.getTasks(), new Predicate<Task>() {
                    @Override
                    public boolean apply(Task input) {
                        return input.getOperationName().equals("cloneVApp");
                    }
                });
                provider.waitForTask(task);
                return null; // TODO: identify vm
            }
            catch( RuntimeException e ) {
                logger.error("Error booting " + vmId + ": " + e.getMessage());
                if( logger.isDebugEnabled() ) {
                    e.printStackTrace();
                }
                throw new CloudException(e);
            }
        }
        finally {
            ctx.close();
        }
    }

    @Override
    public void disableAnalytics(String vmId) throws InternalException, CloudException {
        // no-op
    }

    @Override
    public void enableAnalytics(String vmId) throws InternalException, CloudException {
        // no-op
    }

    @Override
    public String getConsoleOutput(String vmId) throws InternalException, CloudException {
        return "";
    }

    @Override
    public VirtualMachineProduct getProduct(String productId) throws InternalException, CloudException {
        for( Architecture architecture : Architecture.values() ) {
            for( VirtualMachineProduct product : listProducts(architecture) ) {
                if( product.getProductId().equals(productId) ) {
                    return product;
                }
            }
        }
        return null;
    }

    @Override
    public String getProviderTermForServer(Locale locale) {
        return "virtual machine";
    }

    @Override
    public VirtualMachine getVirtualMachine(String vmId) throws InternalException, CloudException {
        for( VirtualMachine vm : listVirtualMachines() ) {
            if( vm.getProviderVirtualMachineId().equals(vmId) ) {
                return vm;
            }
        }
        return null;
    }

    @Override
    public VmStatistics getVMStatistics(String vmId, long from, long to) throws InternalException, CloudException {
        return new VmStatistics();
    }

    @Override
    public Iterable<VmStatistics> getVMStatisticsForPeriod(String vmId, long from, long to) throws InternalException, CloudException {
        return Collections.emptyList();
    }

    private boolean isPublicIp(String ipAddress) {
        if( !ipAddress.startsWith("10.") && !ipAddress.startsWith("192.168.") ) {
            if( ipAddress.startsWith("172.") ) {
                String[] nums = ipAddress.split("\\.");
                
                if( nums.length != 4 ) {
                    return true;
                }
                else {
                    try {
                        int x = Integer.parseInt(nums[1]);
                        
                        if( x < 16 || x > 31 ) {
                            return true;
                        }
                    }
                    catch( NumberFormatException ignore ) {
                        // ignore
                    }
                }
            }
            else {
                return true;
            }  
        }
        return false;
    }
        
    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        try {
            provider.getOrg().getCatalogs();
            return true;
        }
        catch( AuthorizationException e ) {
            return false;
        }
    }

    @Override
    public VirtualMachine launch(String fromMachineImageId, VirtualMachineProduct product, String dataCenterId, String name, String description, String withKeypairId, String inVlanId, boolean withAnalytics, boolean asSandbox, String... firewallIds) throws InternalException, CloudException {
        return launch(fromMachineImageId, product, dataCenterId, name, description, withKeypairId, inVlanId, withAnalytics, asSandbox, firewallIds, new Tag[] { });
    }

    @Override
    public VirtualMachine launch(String fromMachineImageId, VirtualMachineProduct product, String dataCenterId, String name, String description, String withKeypairId, String inVlanId, boolean withAnalytics, boolean asSandbox, String[] firewallIds, Tag... tags) throws InternalException, CloudException {
        RestContext<VCloudDirectorAdminApi, VCloudDirectorAdminAsyncApi> ctx = provider.getCloudClient();
        
        provider.getComputeServices().getImageSupport().listMachineImages();
        try {
            try {
                product = getProduct(product.getProductId());

                InstantiateVAppTemplateParams.Builder<?> options = InstantiateVAppTemplateParams.builder().description(fromMachineImageId);
                VAppTemplate template = ctx.getApi().getVAppTemplateApi().get(provider.toHref(ctx, fromMachineImageId));

                NetworkConnectionSection networkSection = VAppTemplateSupport.getSection(template, NetworkConnectionSection.class);
                for( NetworkConnection c : networkSection.getNetworkConnections() ) {
                    System.out.println("Template connection: " + c.getIpAddressAllocationMode());
                }
                options.powerOn(false);
                options.deploy(false);
                if( inVlanId != null ) {
                    NetworkConnection connection = NetworkConnection.builder().network(inVlanId).build();
                    InstantiationParams instantiate = InstantiationParams.builder()
                            .section(NetworkConnectionSection.builder().networkConnection(connection).build())
                            .build();
                    options.instantiationParams(instantiate);
                }
                VApp app = ctx.getApi().getVdcApi().instantiateVApp(template.getHref(), options.build());
                
                if( app == null ) {
                    throw new CloudException("No vApp was instantiated for " + fromMachineImageId);
                }
                while( app.getStatus().equals(Status.UNRESOLVED) ) {
                    try { Thread.sleep(5000L); }
                    catch( InterruptedException e ) { }
                    try { app = ctx.getApi().getVAppApi().get(app.getHref()); }
                    catch( Throwable ignore ) { }
                }
                app = provider.waitForIdle(ctx, app);
                List<Vm> children = app.getChildren().getVms();
                int i = 0;

                name = provider.validateName(name);                
                for( Vm vm : children ) {
                    i++;
                    vm = provider.waitForIdle(ctx, vm);
                    GuestCustomizationSection s = getSection(vm, GuestCustomizationSection.class);
                    GuestCustomizationSection.Builder<?> sb = s.toBuilder();
                    String n = (children.size() < 2 ? (name + "-" + i) : name);
                    
                    sb.enabled(true);
                    sb.info(name);
                    sb.computerName(n);
                    ctx.getApi().getVmApi().editGuestCustomizationSection(vm.getHref(), sb.build());
                }
                app = provider.waitForIdle(ctx, app);
                VLAN network = null;
                
                if( inVlanId == null ) {
                    for( VLAN n : provider.getNetworkServices().getVlanSupport().listVlans() ) {
                        network = n;
                        break;
                    }
                }
                else {
                    network = provider.getNetworkServices().getVlanSupport().getVlan(inVlanId);                    
                }
                for( Vm vm : children ) {
                    vm = provider.waitForIdle(ctx, vm);

                    NetworkConnectionSection section = ctx.getApi().getVmApi().getNetworkConnectionSection(vm.getHref())
                            .toBuilder()
                            .networkConnections(Sets.<NetworkConnection>newLinkedHashSet())
                            .build();
                    provider.waitForTask(ctx.getApi().getVmApi().editNetworkConnectionSection(vm.getHref(), section));
                    vm = provider.waitForIdle(ctx, vm);
                    
                    NetworkConnection connection = NetworkConnection.builder()
                            .isConnected(true)
		                    .ipAddressAllocationMode(IpAddressAllocationMode.POOL)
		                    .network(network.getName())
		                    .networkConnectionIndex(0)
		                    .build();
                    section = ctx.getApi().getVmApi().getNetworkConnectionSection(vm.getHref())
                            .toBuilder()
                            .networkConnection(connection)
                            .build();
                    provider.waitForTask(ctx.getApi().getVmApi().editNetworkConnectionSection(vm.getHref(), section));
                    vm = provider.waitForIdle(ctx, vm);

                    // FIXME

                    RasdItem cpu = ctx.getApi().getVmApi().getVirtualHardwareSectionCpu(vm.getHref())
                            .toBuilder()
                            .virtualQuantity(BigInteger.valueOf(product.getCpuCount()))
                            .build();
                    provider.waitForTask(ctx.getApi().getVmApi().editVirtualHardwareSectionCpu(vm.getHref(), cpu));
                    vm = provider.waitForIdle(ctx, vm);

                    RasdItem ram = ctx.getApi().getVmApi().getVirtualHardwareSectionCpu(vm.getHref())
                            .toBuilder()
                            .virtualQuantity(BigInteger.valueOf(product.getRamInMb()))
                            .build();
                    provider.waitForTask(ctx.getApi().getVmApi().editVirtualHardwareSectionMemory(vm.getHref(), ram));
                    vm = provider.waitForIdle(ctx, vm);
                }
                app = provider.waitForIdle(ctx, app);
                DeployVAppParams deploy = DeployVAppParams.builder().powerOn().build();
                ctx.getApi().getVAppApi().deploy(app.getHref(), deploy);
                
                Collection<VirtualMachine> vms = toVirtualMachines(ctx, app);
                
                if( vms.isEmpty() ) {
                    return null;
                }
                return vms.iterator().next();
            }
            catch( RuntimeException e ) {
                logger.error("Error launching from " + fromMachineImageId + ": " + e.getMessage());
                if( logger.isDebugEnabled() ) {
                    e.printStackTrace();
                }
                throw new CloudException(e);
            }
        }
        finally {
            ctx.close();
        }
    }
    
    @Override
    public Iterable<String> listFirewalls(String vmId) throws InternalException, CloudException {
        return Collections.emptyList();
    }

    static private Iterable<VirtualMachineProduct> products = null;
    
    @Override
    public Iterable<VirtualMachineProduct> listProducts(Architecture architecture) throws InternalException, CloudException {
        if( products == null ) {
            ArrayList<VirtualMachineProduct> sizes = new ArrayList<VirtualMachineProduct>();
            
            for( int ram : new int[] { 512, 1024, 1536, 2048, 4096, 8192, 12288, 16384 } ) {
                for( int cpu : new int[] { 1, 2, 4, 8 } ) {
                    VirtualMachineProduct product = new VirtualMachineProduct();

                    product.setProductId(ram + ":" + cpu);
                    product.setName(cpu + " CPU, " + ram + "M RAM");
                    product.setDescription(cpu + " CPU, " + ram + "M RAM");
                    product.setCpuCount(cpu);
                    product.setDiskSizeInGb(4);
                    product.setRamInMb(ram);
                    sizes.add(product);
                }
            }
            products = Collections.unmodifiableList(sizes);
        }
        return products;
    }

    @Override
    public Iterable<VirtualMachine> listVirtualMachines() throws InternalException, CloudException {
        RestContext<VCloudDirectorAdminApi, VCloudDirectorAdminAsyncApi> ctx = provider.getCloudClient();
        
        try {
            try {
                ArrayList<VirtualMachine> list = new ArrayList<VirtualMachine>();
                
                for( DataCenter dc : provider.getDataCenterServices().listDataCenters(provider.getContext().getRegionId()) ) {
                    System.out.println("DC=" + dc);
                    Vdc vdc = provider.getDataCenterServices().getVdc(dc.getProviderDataCenterId());
                    System.out.println("dc for " + dc.getProviderDataCenterId() + "=" + vdc);
                    if( vdc != null ) {
                        Set<Reference> refs = vdc.getResourceEntities();
                    
                        if( refs == null ) {
                            return Collections.emptyList();
                        }
                        for( Reference type : Iterables.filter(refs, ReferencePredicates.typeEquals(VCloudDirectorMediaType.VAPP)) ) {
                            VApp app = ctx.getApi().getVAppApi().get(type.getHref());
                                
                            if( app != null ) {
                                list.addAll(toVirtualMachines(ctx, app));
                            }
                        }
                    }
                }
                return list;
            }
            catch( RuntimeException e ) {
                logger.error("Error listing virtual machines in " + provider.getContext().getRegionId() + ": " + e.getMessage());
                if( logger.isDebugEnabled() ) {
                    e.printStackTrace();
                }
                throw new CloudException(e);
            }
        }
        finally {
            ctx.close();
        }
    }

    @Override
    public @Nonnull String[] mapServiceAction(@Nonnull ServiceAction action) {
        return new String[0];
    }

    @Override
    public void pause(String vmId) throws InternalException, CloudException {
        RestContext<VCloudDirectorAdminApi, VCloudDirectorAdminAsyncApi> ctx = provider.getCloudClient();
        
        try {
            try {
                provider.waitForTask(ctx.getApi().getVAppApi().powerOff(provider.toHref(ctx, vmId)));
            }
            catch( RuntimeException e ) {
                logger.error("Error booting " + vmId + ": " + e.getMessage());
                if( logger.isDebugEnabled() ) {
                    e.printStackTrace();
                }
                throw new CloudException(e);
            }
        }
        finally {
            ctx.close();
        }
    }

    @Override
    public void reboot(String vmId) throws CloudException, InternalException {
        RestContext<VCloudDirectorAdminApi, VCloudDirectorAdminAsyncApi> ctx = provider.getCloudClient();
        
        try {
            try {
                ctx.getApi().getVAppApi().reboot(provider.toHref(ctx, vmId));
            }
            catch( RuntimeException e ) {
                logger.error("Error rebooting " + vmId + ": " + e.getMessage());
                if( logger.isDebugEnabled() ) {
                    e.printStackTrace();
                }
                throw new CloudException(e);
            }
        }
        finally {
            ctx.close();
        }
    }

    @Override
    public boolean supportsAnalytics() throws CloudException, InternalException {
        return false;
    }

    @Override
    public void terminate(String vmId) throws InternalException, CloudException {
        RestContext<VCloudDirectorAdminApi, VCloudDirectorAdminAsyncApi> ctx = provider.getCloudClient();
        
        try {
            try {
                Vm vm = ctx.getApi().getVmApi().get(provider.toHref(ctx, vmId));
                
                if( vm == null ) {
                    throw new CloudException("No such VM: " + vmId);
                }
                VApp parent = ctx.getApi().getVAppApi().get(vm.getVAppParent().getHref());
                
                if( parent.getType().equals(VCloudDirectorMediaType.VAPP) ) {
                    parent = provider.waitForIdle(ctx, parent);
                    vm = ctx.getApi().getVmApi().get(vm.getHref());
                    if( vm.getStatus().equals(Status.POWERED_ON) ) {
                        vm = provider.waitForIdle(ctx, vm);
                        provider.waitForTask(ctx.getApi().getVAppApi().powerOff(vm.getHref()));
                    }
                    vm = provider.waitForIdle(ctx, vm);
                    parent = ctx.getApi().getVAppApi().get(parent.getHref());
                    
                    int count = 0;
                    
                    for( Vm child : parent.getChildren().getVms() ) {
                        if( child.getStatus().equals(Status.POWERED_ON) ) {
                            count++;
                        }
                    }
                    if( count < 1 ) {
                        parent = provider.waitForIdle(ctx, parent);
                        UndeployVAppParams undeploy = UndeployVAppParams.builder()
                                .undeployPowerAction(UndeployVAppParams.PowerAction.POWER_OFF)
                                .build();
                        try { provider.waitForTask(ctx.getApi().getVAppApi().undeploy(parent.getHref(), undeploy)); }
                        catch( Throwable ignore ) { }
                        parent = provider.waitForIdle(ctx, parent);
                        for( Vm child : parent.getChildren().getVms() ) {
                            provider.waitForIdle(ctx, child);
                        }
                        boolean running = true;
                        
                        while( running ) {
                            try {
                                provider.waitForTask(ctx.getApi().getVAppApi().remove(parent.getHref()));
                                running = false;
                            }
                            catch( IllegalStateException vCloudLies ) {
                                try { Thread.sleep(5000L); }
                                catch( InterruptedException e ) { }
                            }
                        }
                    }
                }
                else {
                    if( vm.getStatus().equals(Status.POWERED_ON) ){
                        vm = provider.waitForIdle(ctx, vm);
                        provider.waitForTask(ctx.getApi().getVAppApi().powerOff(vm.getHref()));
                    }
                    vm = provider.waitForIdle(ctx, vm);
                    if( vm.getStatus().equals(Status.DEPLOYED) ){
                        UndeployVAppParams undeploy = UndeployVAppParams.builder()
                                .undeployPowerAction(UndeployVAppParams.PowerAction.POWER_OFF)
                                .build();
                        provider.waitForTask(ctx.getApi().getVAppApi().undeploy(vm.getHref(), undeploy));
                        while( vm != null && vm.getStatus().equals(Status.DEPLOYED) ) {
                            try { Thread.sleep(5000L); }
                            catch( InterruptedException e ) { }
                            try { vm = ctx.getApi().getVmApi().get(vm.getHref()); }
                            catch( Throwable ignore ) { }
                        }                    
                    }
                }
            }
            catch( RuntimeException e ) {
                logger.error("Error terminating " + vmId + ": " + e.getMessage());
                if( logger.isDebugEnabled() ) {
                    e.printStackTrace();
                }
                throw new CloudException(e);
            }
        }
        finally {
            ctx.close();
        }
    }
    
    private VirtualMachine toVirtualMachine(RestContext<VCloudDirectorAdminApi, VCloudDirectorAdminAsyncApi> ctx, VApp app, Vm vcloudVm) throws CloudException, InternalException {
        if( vcloudVm == null ) {
            return null;
        }
        VirtualMachine vm = new VirtualMachine();
        String vmId = provider.toId(ctx, vcloudVm.getHref());
        URI vdcURI = Iterables.find(app.getLinks(),
                Predicates.and(LinkPredicates.relEquals(Link.Rel.UP),
                        LinkPredicates.typeEquals(VCloudDirectorMediaType.VDC))).getHref();
        
        vm.setProviderVirtualMachineId(vmId);
        vm.setName(vcloudVm.getName());
        vm.setDescription(vcloudVm.getDescription());
        vm.setProviderOwnerId(provider.getOrg().getName());
        vm.setProviderRegionId(provider.getContext().getRegionId());
        vm.setProviderAssignedIpAddressId(null);
        vm.setProviderDataCenterId(provider.toId(ctx, vdcURI));
        vm.setPlatform(Platform.guess(vm.getName() + " " + vm.getDescription()));
        vm.setArchitecture(Architecture.I64);
        vm.setClonable(true);
        vm.setImagable(true);
        vm.setPausable(true);
        vm.setPersistent(true);
        vm.setRebootable(true);
        if( vm.getName() == null ) {
            vm.setName(app.getName());
            if( vm.getName() == null ) {
                vm.setName(vmId);
            }
        }
        if( vm.getDescription() == null ) {
            vm.setDescription(app.getDescription());
            if( vm.getDescription() == null ) {
                vm.setDescription(vm.getName());
            }
        }
        
        
        VirtualHardwareSection hardware = getSection(vcloudVm, VirtualHardwareSection.class);
        long ram = 256, cpus = 1;
        
        for( ResourceAllocationSettingData allocation : hardware.getItems() ) {
            if( allocation.getResourceType().equals(ResourceType.MEMORY) ) {
                ram = allocation.getVirtualQuantity().intValue();
            }
            else if( allocation.getResourceType().equals(ResourceType.PROCESSOR) ) {
                cpus = allocation.getVirtualQuantity().intValue();
            }
        }
        VirtualMachineProduct product = getProduct(ram + ":" + cpus);
      
        if( product == null ) {
            product = new VirtualMachineProduct();
            product.setCpuCount((int)cpus);
            product.setRamInMb((int)ram);
            product.setProductId(ram + ":" + cpus);
            product.setDescription(ram + ":" + cpus);
            product.setName(product.getDescription());
            product.setDiskSizeInGb(4);
        }
        vm.setProduct(product);
        ArrayList<String> publicIpAddresses = new ArrayList<String>();
        ArrayList<String> privateIpAddresses = new ArrayList<String>();
        String externalIp = null, providerNetworkId = null;
        
        System.out.println("Checking network connections: " + getSection(vcloudVm, NetworkConnectionSection.class).getNetworkConnections());
        
        for( NetworkConnection c : getSection(vcloudVm, NetworkConnectionSection.class).getNetworkConnections() ) {
            System.out.println("EXT=" + c.getExternalIpAddress());
            System.out.println("Assigned=" + c.getIpAddress());
            System.out.println("Model=" + c.getIpAddressAllocationMode());
            System.out.println("Network=" + c.getNetwork());

            if( c.getNetworkConnectionIndex() == getSection(vcloudVm, NetworkConnectionSection.class).getPrimaryNetworkConnectionIndex() ) {
                Iterable<VLAN> vlans = provider.getNetworkServices().getVlanSupport().listVlans();
                
                for( VLAN vlan : vlans ) {
                    if( vlan.getName().equalsIgnoreCase(c.getNetwork()) ) {
                        providerNetworkId = vlan.getProviderVlanId();
                    }
                }
                if( c.getExternalIpAddress() != null ) {
                    externalIp = c.getExternalIpAddress();
                }
                if( c.getIpAddress() != null ) {
                    String addr = c.getIpAddress();
                    
                    if( isPublicIp(addr) ) {
                        publicIpAddresses.add(addr);
                    }
                    else {
                        privateIpAddresses.add(addr);
                    }
                }
            }
        }
        String imageId = app.getDescription();

        if( imageId != null ) {
            VAppTemplate template;
            
            try {
                template = ctx.getApi().getVAppTemplateApi().get(provider.toHref(ctx, imageId));
            }
            catch( RuntimeException e ) {
                template = null;
            }
            if( template != null ) {
                VAppTemplateSupport support = provider.getComputeServices().getImageSupport();
                
                vm.setProviderMachineImageId(imageId);
                vm.setArchitecture(support.getArchitecture(template));
                vm.setPlatform(support.getPlatform(template));
            }
            else if( imageId.startsWith("/vAppTemplate") ) {
                vm.setProviderMachineImageId(imageId);
            }
            else {
                vm.setProviderMachineImageId("/vAppTemplate/" + provider.getContext().getAccountNumber() + "-unknown");                
            }
        }
        else {
            vm.setProviderMachineImageId("/vAppTemplate/" + provider.getContext().getAccountNumber() + "-unknown");
        }
        
        vm.setPrivateIpAddresses(privateIpAddresses.toArray(new String[0]));
        if( externalIp != null ) {
            vm.setPublicIpAddresses(new String[] { externalIp });
        }
        else {
            vm.setPublicIpAddresses(publicIpAddresses.toArray(new String[0]));
        }
        vm.setProviderVlanId(providerNetworkId);
        vm.setRootPassword(getSection(vcloudVm, GuestCustomizationSection.class).getAdminPassword());
        vm.setRootUser(vm.getPlatform().isWindows() ? "administrator" : "root");
        vm.setTags(new HashMap<String,String>());
        switch( vcloudVm.getStatus() ) {
        case POWERED_ON:
            vm.setCurrentState(VmState.RUNNING);
            break;
        case POWERED_OFF:
        case SUSPENDED:
            vm.setCurrentState(VmState.PAUSED);
            break;
        case FAILED_CREATION:
            vm.setCurrentState(VmState.TERMINATED);
            break;
        default:
            logger.warn("Unknown VM status: " + app.getStatus() + " for " + app.getHref().toASCIIString());
            vm.setCurrentState(VmState.PENDING);
            break;
        }
        long created = System.currentTimeMillis();
        long deployed = -1L, paused = -1L;
        
        for( Task task : vcloudVm.getTasks() ) {
            if( task == null ) { 
                continue;
            }
            Date d = task.getStartTime();
            
            if( d != null ) {
                String txt = (task.getName() + " " + task.getType()).toLowerCase();
                long when = d.getTime();
                
                if( txt.contains("deploy") ) {
                    if( when > deployed ) {
                        deployed = when;
                    }
                }
                if( txt.contains("poweroff") ) {
                    if( when > paused ) {
                        paused = when;
                    }
                }
                if( when < created ) {
                    created = d.getTime();
                }
            }
        }
        vm.setLastPauseTimestamp(paused);
        vm.setLastBootTimestamp(deployed);
        vm.setCreationTimestamp(created);
        vm.setTerminationTimestamp(0L);
        return vm;
    }
    
    private Collection<VirtualMachine> toVirtualMachines(RestContext<VCloudDirectorAdminApi, VCloudDirectorAdminAsyncApi> ctx, VApp app) throws CloudException, InternalException {
        ArrayList<VirtualMachine> vms = new ArrayList<VirtualMachine>();
        
        for( Vm vm : app.getChildren().getVms() ) {
            VirtualMachine v = toVirtualMachine(ctx, app, vm);
            
            if( v != null ) {
                vms.add(v);
            }
        }
        return vms;
    }

    public static <S extends SectionType> S getSection(AbstractVAppType vapp, Class<S> sectionClass) {
        S section = (S) Iterables.find(vapp.getSections(), Predicates.instanceOf(sectionClass));
        return section;
    }

}
