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

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.lang.model.type.ReferenceType;

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
import org.jclouds.cim.ResourceAllocationSettingData;
import org.jclouds.cim.ResourceAllocationSettingData.ResourceType;
import org.jclouds.ovf.VirtualHardwareSection;
import org.jclouds.rest.AuthorizationException;
import org.jclouds.rest.RestContext;
import org.jclouds.vcloud.director.v1_5.VCloudDirectorMediaType;
import org.jclouds.vcloud.director.v1_5.admin.VCloudDirectorAdminAsyncClient;
import org.jclouds.vcloud.director.v1_5.admin.VCloudDirectorAdminClient;
import org.jclouds.vcloud.director.v1_5.domain.CloneVAppParams;
import org.jclouds.vcloud.director.v1_5.domain.GuestCustomizationSection;
import org.jclouds.vcloud.director.v1_5.domain.InstantiateVAppTemplateParams;
import org.jclouds.vcloud.director.v1_5.domain.InstantiationParams;
import org.jclouds.vcloud.director.v1_5.domain.NetworkConnection;
import org.jclouds.vcloud.director.v1_5.domain.NetworkConnection.IpAddressAllocationMode;
import org.jclouds.vcloud.director.v1_5.domain.NetworkConnectionSection;
import org.jclouds.vcloud.director.v1_5.domain.NetworkConnectionSection.Builder;
import org.jclouds.vcloud.director.v1_5.domain.Reference;
import org.jclouds.vcloud.director.v1_5.domain.ResourceEntityType.Status;
import org.jclouds.vcloud.director.v1_5.domain.Task;
import org.jclouds.vcloud.director.v1_5.domain.VApp;
import org.jclouds.vcloud.director.v1_5.domain.VAppTemplate;
import org.jclouds.vcloud.director.v1_5.domain.Vdc;
import org.jclouds.vcloud.director.v1_5.domain.Vm;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;

public class VmSupport implements VirtualMachineSupport {
    static private final Logger logger = Logger.getLogger(VirtualMachineSupport.class);
    
    private VCloudDirector provider;
    
    VmSupport(VCloudDirector provider) { this.provider = provider; }
    
    @Override
    public void boot(String vmId) throws InternalException, CloudException {
        RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx = provider.getCloudClient();
        
        try {
            try {
                Task task = ctx.getApi().getVmClient().powerOnVm(provider.toHref(ctx, vmId));
                
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
        RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx = provider.getCloudClient();
        
        try {
            try {
                CloneVAppParams.Builder<?> options = CloneVAppParams.builder().description(description).name(name);
                
                if( powerOn ) {
                    options.powerOn();
                }
                // note this says vmId, but the copy operation is on the vApp. you might want to rename this variable accordingly
                URI vAppUri = null;
                VApp clone = ctx.getApi().getVdcClient().cloneVApp(vAppUri, options.build());

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
        RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx = provider.getCloudClient();
        
        provider.getComputeServices().getImageSupport().listMachineImages();
        try {
            try {
                product = getProduct(product.getProductId());

                InstantiateVAppTemplateParams.Builder<?> options = InstantiateVAppTemplateParams.builder().description(fromMachineImageId);
                VAppTemplate template = ctx.getApi().getVAppTemplateClient().getVAppTemplate(provider.toHref(ctx, fromMachineImageId));

                for( VAppTemplate child : template.getChildren() ) {
                    NetworkConnectionSection section = (NetworkConnectionSection) Iterables.find(child.getSections(), Predicates.instanceOf(NetworkConnectionSection.class));
                    for( NetworkConnection c : section.getNetworkConnections() ) {
                        System.out.println("Template connection: " + c.getIpAddressAllocationMode());
                    }
                }
                options.powerOn(false);
                options.deploy(false);
                if( inVlanId != null ) {
                    NetworkConnection connection = NetworkConnection.builder().network(inVlanId).build();
                    
                    options.instantiationParams(InstantiationParams.builder().section(NetworkConnectionSection.builder().networkConnection(connection).build()).build());
                }
                VApp app = ctx.getApi().getVdcClient().instantiateVApp(template.getHref(), options.build());
                
                if( app == null ) {
                    throw new CloudException("No vApp was instantiated for " + fromMachineImageId);
                }
                while( app.getStatus().equals(Status.UNRESOLVED) ) {
                    try { Thread.sleep(5000L); }
                    catch( InterruptedException e ) { }
                    try { app = ctx.getApi().getVAppClient().getVApp(app.getHref()); }
                    catch( Throwable ignore ) { }
                }
                app = provider.waitForIdle(ctx, app);
                List<Vm> children = app.getChildren().getVms();
                int i = 0;

                name = provider.validateName(name);                
                for( Vm vm : children ) {
                    i++;
                    vm = provider.waitForIdle(ctx, vm);
                    GuestCustomizationSection s = vm.getGuestCustomizationSection();
                    String n = (children.size() < 2 ? (name + "-" + i) : name);
                    
                    s.setEnabled(true);
                    s.setInfo(name);
                    s.setComputerName(n);
                    ctx.getApi().getVmClient().updateGuestCustomizationOfVm(s, vm.getHref());
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
                    
                    ArrayList<NetworkConnection> connections = new ArrayList<NetworkConnection>();
                    NetworkConnectionSection section = vm.getNetworkConnectionSection();
                    Builder sectionBuilder = section.toBuilder();                    

                    sectionBuilder.connections(connections);
                    section = sectionBuilder.build();
                    provider.waitForTask(ctx.getApi().getVmClient().updateNetworkConnectionOfVm(section, vm.getHref()));
                    vm = provider.waitForIdle(ctx, vm);
                    section = vm.getNetworkConnectionSection();
                    sectionBuilder = section.toBuilder();
                    connections.clear();
                    
                    NetworkConnection.Builder b = NetworkConnection.builder().connected(true);
                    
                    b.ipAddressAllocationMode(IpAddressAllocationMode.POOL);
                    b.network(network.getName());
                    b.networkConnectionIndex(0);
                    connections.add(b.build());
                    
                    sectionBuilder.connections(connections);
                    section = sectionBuilder.build();
                    provider.waitForTask(ctx.getApi().getVmClient().updateNetworkConnectionOfVm(section, vm.getHref()));
                    vm = provider.waitForIdle(ctx, vm);
                    provider.waitForTask(ctx.getApi().getVmClient().updateCPUCountOfVm(product.getCpuCount(), vm.getHref()));
                    vm = provider.waitForIdle(ctx, vm);
                    provider.waitForTask(ctx.getApi().getVmClient().updateMemoryMBOfVm(product.getRamInMb(), vm.getHref()));
                    vm = provider.waitForIdle(ctx, vm);
                }
                app = provider.waitForIdle(ctx, app);
                ctx.getApi().getVAppClient().deployAndPowerOnVApp(app.getHref());
                
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
        RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx = provider.getCloudClient();
        
        try {
            try {
                ArrayList<VirtualMachine> list = new ArrayList<VirtualMachine>();
                
                for( DataCenter dc : provider.getDataCenterServices().listDataCenters(provider.getContext().getRegionId()) ) {
                    System.out.println("DC=" + dc);
                    Vdc vdc = provider.getDataCenterServices().getVdc(dc.getProviderDataCenterId());
                    System.out.println("dc for " + dc.getProviderDataCenterId() + "=" + vdc);
                    if( vdc != null ) {
                        Map<String, ReferenceType> map = vdc.getResourceEntities();
                    
                        if( map == null ) {
                            return Collections.emptyList();
                        }
                        for( Reference type : refs ) {
                            if( type.getType().equals(VCloudDirectorMediaType.VAPP) ) {
                                VApp app = ctx.getApi().getVAppClient().getVApp(type.getHref());
                                
                                if( app != null ) {
                                    list.addAll(toVirtualMachines(ctx, app));
                               }
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
        RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx = provider.getCloudClient();
        
        try {
            try {
                provider.waitForTask(ctx.getApi().getVmClient().powerOffVm(provider.toHref(ctx, vmId)));
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
        RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx = provider.getCloudClient();
        
        try {
            try {
                ctx.getApi().getVmClient().rebootVm(provider.toHref(ctx, vmId));
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
        RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx = provider.getCloudClient();
        
        try {
            try {
                Vm vm = ctx.getApi().getVmClient().getVm(provider.toHref(ctx, vmId));
                
                if( vm == null ) {
                    throw new CloudException("No such VM: " + vmId);
                }
                VApp parent = ctx.getApi().getVAppClient().getVApp(vm.getParent().getHref());
                
                if( parent.getType().equals(VCloudDirectorMediaType.VAPP) ) {
                    parent = provider.waitForIdle(ctx, parent);
                    vm = ctx.getApi().getVmClient().getVm(vm.getHref());
                    if( vm.getStatus().equals(Status.ON) ) {
                        vm = provider.waitForIdle(ctx, vm);
                        provider.waitForTask(ctx.getApi().getVmClient().powerOffVm(vm.getHref()));
                    }
                    vm = provider.waitForIdle(ctx, vm);
                    parent = ctx.getApi().getVAppClient().getVApp(parent.getHref());
                    
                    int count = 0;
                    
                    for( Vm child : parent.getChildren().getVms() ) {
                        if( child.getStatus().equals(Status.ON) ) {
                            count++;
                        }
                    }
                    if( count < 1 ) {
                        parent = provider.waitForIdle(ctx, parent);
                        try { provider.waitForTask(ctx.getApi().getVAppClient().undeployVApp(parent.getHref())); }
                        catch( Throwable ignore ) { }
                        parent = provider.waitForIdle(ctx, parent);
                        for( Vm child : parent.getChildren().getVms() ) {
                            provider.waitForIdle(ctx, child);
                        }
                        boolean running = true;
                        
                        while( running ) {
                            try {
                                provider.waitForTask(ctx.getApi().getVAppClient().deleteVApp(parent.getHref()));
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
                    if( vm.getStatus().equals(Status.ON) ){
                        vm = provider.waitForIdle(ctx, vm);
                        provider.waitForTask(ctx.getApi().getVmClient().powerOffVm(vm.getHref()));
                    }
                    vm = provider.waitForIdle(ctx, vm);
                    if( vm.getStatus().equals(Status.DEPLOYED) ){
                        provider.waitForTask(ctx.getApi().getVmClient().undeployVm(vm.getHref()));
                        while( vm != null && vm.getStatus().equals(Status.DEPLOYED) ) {
                            try { Thread.sleep(5000L); }
                            catch( InterruptedException e ) { }
                            try { vm = ctx.getApi().getVmClient().getVm(vm.getHref()); }
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
    
    private VirtualMachine toVirtualMachine(RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx, VApp app, Vm vcloudVm) throws CloudException, InternalException {
        if( vcloudVm == null ) {
            return null;
        }
        VirtualMachine vm = new VirtualMachine();
        String vmId = provider.toId(ctx, vcloudVm.getHref());
        
        vm.setProviderVirtualMachineId(vmId);
        vm.setName(vcloudVm.getName());
        vm.setDescription(vcloudVm.getDescription());
        vm.setProviderOwnerId(provider.getOrg().getName());
        vm.setProviderRegionId(provider.getContext().getRegionId());
        vm.setProviderAssignedIpAddressId(null);
        vm.setProviderDataCenterId(provider.toId(ctx, app.getVDC().getHref()));
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
        
        
        VirtualHardwareSection hardware = vcloudVm.getVirtualHardwareSection();
        long ram = 256, cpus = 1;
        
        for( ResourceAllocationSettingData allocation : hardware.getItems() ) {
            if( allocation.getResourceType().equals(ResourceType.MEMORY) ) {
                ram = allocation.getVirtualQuantity();
            }
            else if( allocation.getResourceType().equals(ResourceType.PROCESSOR) ) {
                cpus = allocation.getVirtualQuantity();
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
        
        System.out.println("Checking network connections: " + vcloudVm.getNetworkConnectionSection().getConnections());
        
        for( NetworkConnection c : vcloudVm.getNetworkConnectionSection().getConnections() ) {
            System.out.println("EXT=" + c.getExternalIpAddress());
            System.out.println("Assigned=" + c.getIpAddress());
            System.out.println("Model=" + c.getIpAddressAllocationMode());
            System.out.println("Network=" + c.getNetwork());

            if( c.getNetworkConnectionIndex() == vcloudVm.getNetworkConnectionSection().getPrimaryNetworkConnectionIndex() ) {
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
                template = ctx.getApi().getVAppTemplateClient().getVAppTemplate(provider.toHref(ctx, imageId));
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
        vm.setRootPassword(vcloudVm.getGuestCustomizationSection().getAdminPassword());
        vm.setRootUser(vm.getPlatform().isWindows() ? "administrator" : "root");
        vm.setTags(new HashMap<String,String>());
        switch( Status.fromValue(vcloudVm.getStatus()) ) {
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
    
    private Collection<VirtualMachine> toVirtualMachines(RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx, VApp app) throws CloudException, InternalException {
        ArrayList<VirtualMachine> vms = new ArrayList<VirtualMachine>();
        
        for( Vm vm : app.getChildren().getVms() ) {
            VirtualMachine v = toVirtualMachine(ctx, app, vm);
            
            if( v != null ) {
                vms.add(v);
            }
        }
        return vms;
    }

}
