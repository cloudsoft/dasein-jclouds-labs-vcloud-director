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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.log4j.Logger;
import org.dasein.cloud.AsynchronousTask;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.CloudProvider;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.OperationNotSupportedException;
import org.dasein.cloud.compute.Architecture;
import org.dasein.cloud.compute.MachineImage;
import org.dasein.cloud.compute.MachineImageFormat;
import org.dasein.cloud.compute.MachineImageState;
import org.dasein.cloud.compute.MachineImageSupport;
import org.dasein.cloud.compute.MachineImageType;
import org.dasein.cloud.compute.Platform;
import org.dasein.cloud.compute.VirtualMachine;
import org.dasein.cloud.identity.ServiceAction;
import org.dasein.cloud.jclouds.vcloud.director.VCloudDirector;
import org.jclouds.dmtf.ovf.SectionType;
import org.jclouds.rest.AuthorizationException;
import org.jclouds.rest.RestContext;
import org.jclouds.vcloud.director.v1_5.VCloudDirectorMediaType;
import org.jclouds.vcloud.director.v1_5.admin.VCloudDirectorAdminAsyncClient;
import org.jclouds.vcloud.director.v1_5.admin.VCloudDirectorAdminClient;
import org.jclouds.vcloud.director.v1_5.domain.Catalog;
import org.jclouds.vcloud.director.v1_5.domain.CatalogItem;
import org.jclouds.vcloud.director.v1_5.domain.Link;
import org.jclouds.vcloud.director.v1_5.domain.Reference;
import org.jclouds.vcloud.director.v1_5.domain.ResourceEntity.Status;
import org.jclouds.vcloud.director.v1_5.domain.Task;
import org.jclouds.vcloud.director.v1_5.domain.VApp;
import org.jclouds.vcloud.director.v1_5.domain.VAppTemplate;
import org.jclouds.vcloud.director.v1_5.domain.Vdc;
import org.jclouds.vcloud.director.v1_5.domain.Vm;
import org.jclouds.vcloud.director.v1_5.domain.network.NetworkConnection;
import org.jclouds.vcloud.director.v1_5.domain.org.AdminOrg;
import org.jclouds.vcloud.director.v1_5.domain.org.Org;
import org.jclouds.vcloud.director.v1_5.domain.params.CaptureVAppParams;
import org.jclouds.vcloud.director.v1_5.domain.params.DeployVAppParams;
import org.jclouds.vcloud.director.v1_5.domain.params.UndeployVAppParams;
import org.jclouds.vcloud.director.v1_5.domain.params.UndeployVAppParams.PowerAction;
import org.jclouds.vcloud.director.v1_5.domain.section.NetworkConnectionSection;
import org.jclouds.vcloud.director.v1_5.domain.section.OperatingSystemSection;
import org.jclouds.vcloud.director.v1_5.predicates.LinkPredicates;

import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

public class VAppTemplateSupport implements MachineImageSupport {
    static private final Logger logger = Logger.getLogger(VAppTemplateSupport.class);
    
    static public final String TEMPLATE = "vAppTemplate";
    
    private VCloudDirector provider;
    
    VAppTemplateSupport(@Nonnull VCloudDirector provider) { this.provider = provider; }
    
    @Override
    public void downloadImage(@Nonnull String machineImageId, @Nonnull OutputStream toOutput) throws CloudException, InternalException {
        throw new OperationNotSupportedException("Not supported");
    }

    private @Nullable Catalog findCatalog(@Nonnull RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx) throws CloudException {
        Set<Reference> refs = provider.getOrg().getCatalogs();
        
        if( refs == null ) {
            return null;
        }
        
        for( Reference ref : refs ) {
            Catalog c = ctx.getApi().getCatalogClient().getCatalog(ref.getHref()); 
            
            if( !c.isPublished() ) {
                return c;
            }
        }
        return null;
    }
    
    @Override
    public @Nullable MachineImage getMachineImage(@Nonnull String machineImageId) throws CloudException, InternalException {
        RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx = provider.getCloudClient();
        
        try {
            try {
                VAppTemplate template = ctx.getApi().getVAppTemplateClient().getVAppTemplate(provider.toHref(ctx, machineImageId));
                
                if( template == null ) {
                    return null;
                }
                URI vdcURI = Iterables.find(template.getLinks(),
                        Predicates.and(LinkPredicates.relEquals(Link.Rel.UP),
                                LinkPredicates.typeEquals(VCloudDirectorMediaType.VDC))).getHref();
                Vdc vdc = ctx.getApi().getVdcClient().getVdc(vdcURI);
                URI orgURI = Iterables.find(vdc.getLinks(),
                        Predicates.and(LinkPredicates.relEquals(Link.Rel.UP),
                                LinkPredicates.typeEquals(VCloudDirectorMediaType.VDC))).getHref();
                AdminOrg org = ctx.getApi().getOrgClient().getOrg(orgURI);
                
                return toMachineImage(ctx, org, template);
            }
            catch( AuthorizationException e ) {
                return null;
            }
            catch( RuntimeException e ) {
                logger.error("Error looking up machine image " + machineImageId + ": " + e.getMessage());
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
    public @Nonnull String getProviderTermForImage(@Nonnull Locale locale) {
        return "vApp template";
    }

    @Override
    public boolean hasPublicLibrary() {
        return false;
    }

    @Override
    public @Nonnull AsynchronousTask<String> imageVirtualMachine(@Nonnull String vmId, @Nonnull String name, @Nonnull String description) throws CloudException, InternalException {
        final AsynchronousTask<String> imageTask = new AsynchronousTask<String>();
        final String f_vmId = vmId;
        final String f_name = name;
        final String f_desc = description;
        
        imageTask.setStartTime(System.currentTimeMillis());
        provider.hold();
        Thread t = new Thread() {
            public void run() {
                try {
                    MachineImage image = executeImage(f_vmId, f_name, f_desc);
                    
                    imageTask.completeWithResult(image.getProviderMachineImageId());
                }
                catch( Throwable t ) {
                    imageTask.complete(t);
                }
                finally {
                    provider.release();
                }
            }
        };
        
        t.setName("Image " + vmId + " - " + name);
        t.setDaemon(true);
        t.start();
        return imageTask;
    }
    
    private @Nonnull MachineImage executeImage(@Nonnull String vmId, @Nonnull String name, @Nonnull String description) throws CloudException, InternalException {
        RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx = provider.getCloudClient();
        
        try {
            try {
                VirtualMachine vm = provider.getComputeServices().getVirtualMachineSupport().getVirtualMachine(vmId);
                Vm vcloudVm = ctx.getApi().getVmClient().getVm(provider.toHref(ctx, vmId));
                VApp parent = ctx.getApi().getVAppClient().getVApp(vcloudVm.getVAppParent().getHref());
                
                if( parent.getStatus().equals(Status.POWERED_ON) ) {
                    provider.waitForTask(ctx.getApi().getVAppClient().powerOff(parent.getHref()));
                }
                UndeployVAppParams params = UndeployVAppParams.builder().undeployPowerAction(PowerAction.POWER_OFF).build();
                provider.waitForTask(ctx.getApi().getVAppClient().undeploy(parent.getHref(), params));
                HashMap<String,Collection<NetworkConnection.Builder>> oldBuilders = new HashMap<String,Collection<NetworkConnection.Builder>>();
                for( Vm child : parent.getChildren().getVms() ) {
                    ArrayList<NetworkConnection.Builder> list = new ArrayList<NetworkConnection.Builder>();
                    NetworkConnectionSection section = (NetworkConnectionSection) Iterables.find(child.getSections(), Predicates.instanceOf(NetworkConnectionSection.class));
                    for( NetworkConnection c : section.getNetworkConnections() ) {
                        NetworkConnection.Builder builder = NetworkConnection.builder().fromNetworkConnection(c);
                        list.add(builder);
                    }
                    oldBuilders.put(provider.toId(ctx, child.getHref()), list);
                }
                /*
                 * IPAddress allocation mode nonsense
                for( Vm child : parent.getChildren() ) {
                    Builder sectionBuilder = child.getNetworkConnectionSection().toBuilder();
                    ArrayList<NetworkConnection> connections = new ArrayList<NetworkConnection>();
                    
                    for( NetworkConnection c : child.getNetworkConnectionSection().getConnections() ) {
                        NetworkConnection.Builder builder = NetworkConnection.Builder.fromNetworkConnection(c);

                        builder.connected(false);
                        builder.ipAddressAllocationMode(IpAddressAllocationMode.NONE);
                        connections.add(builder.build());
                    }
                    sectionBuilder.connections(connections);
                    provider.waitForTask(ctx.getApi().getVmClient().updateNetworkConnectionOfVm(sectionBuilder.build(), child.getHref()));
                }
                */
                /*
                System.out.println("Powering it back on...");
                provider.waitForTask(ctx.getApi().getVAppClient().deployAndPowerOnVApp(parent.getHref()));
                parent = provider.waitForIdle(ctx, parent);
                System.out.println("Turning it back off...");
                provider.waitForTask(ctx.getApi().getVAppClient().undeployAndSaveStateOfVApp(parent.getHref())); 
                */               
                parent = provider.waitForIdle(ctx, parent);
                if( logger.isInfoEnabled() ) {
                    logger.info("Building template from " + vm);
                }
                VAppTemplate template;
                try {
                    CaptureVAppParams capture = CaptureVAppParams.builder().description(description).build();
                     
                    template = ctx.getApi().getVdcClient().captureVApp(parent.getHref(), capture);
                    
                    if( logger.isDebugEnabled() ) {
                        logger.debug("Template=" + template);
                    }
                    Catalog catalog = findCatalog(ctx);
                    
                    if( logger.isInfoEnabled() ) {
                        logger.info("Adding " + template + " to catalog " + catalog);
                    }
                    if( catalog != null ) {
                        // note you can also add properties here, if you want
                        Reference ref = Reference.builder().fromEntity(template).build();
                        CatalogItem item = CatalogItem.builder().name(name).description(description).entity(ref).build();
                        ctx.getApi().getCatalogClient().addCatalogItem(catalog.getHref(), item);
                        if( logger.isInfoEnabled() ) {
                            logger.info("Template added to catalog");
                        }
                    }
                    else {
                        logger.warn("No catalog exists for this template");
                    }
                }
                finally {
                    if( logger.isInfoEnabled() ) {
                        logger.info("Turning source VM back on");
                    }
                    try {
                        parent = provider.waitForIdle(ctx, parent);
                        for( Vm child : parent.getChildren().getVms() ) {
                            child = provider.waitForIdle(ctx, child);
                            
                            String id = provider.toId(ctx, child.getHref());
                            Collection<NetworkConnection.Builder> builders = oldBuilders.get(id);
                            Set<NetworkConnection> connections = Sets.newLinkedHashSet();
                            
                            for( NetworkConnection.Builder builder : builders ) {
                                builder.isConnected(true);
                                connections.add(builder.build());
                            }
                            NetworkConnectionSection section = VmSupport.getSection(child, NetworkConnectionSection.class)
                                    .toBuilder()
                                    .networkConnections(connections)
                                    .build();
                            if( logger.isInfoEnabled() ) {
                                logger.info("Resetting network connection for " + child);
                            }
                            provider.waitForTask(ctx.getApi().getVmClient().modifyNetworkConnectionSection(child.getHref(), section));                    
                        }
                        parent = provider.waitForIdle(ctx, parent);
                        try {
                            logger.info("Powering VM " + parent + " on");
                            DeployVAppParams deploy = DeployVAppParams.builder().powerOn().build();
                            provider.waitForTask(ctx.getApi().getVAppClient().deploy(parent.getHref(), deploy));
                        }
                        catch( Throwable t ) {
                            logger.warn("Failed to power on VM " + parent);
                        }
                    }
                    catch( Throwable t ) {
                        logger.warn("Error upading network connection for source VM: " + t.getMessage());
                        if( logger.isDebugEnabled() ) {
                            t.printStackTrace();
                        }
                    }
                }
                if( logger.isInfoEnabled() ) {
                    logger.info("Populating dasein image for new template: " + template);
                }
                return toMachineImage(ctx, provider.getOrg(vm.getProviderOwnerId()), ctx.getApi().getVAppTemplateClient().getVAppTemplate(template.getHref()));
            }
            catch( RuntimeException e ) {
                logger.error("Error creating template from " + vmId + ": " + e.getMessage());
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
    public AsynchronousTask<String> imageVirtualMachineToStorage(String vmId, String name, String description, String directory) throws CloudException, InternalException {
        throw new OperationNotSupportedException("Not supported");
    }

    @Override
    public String installImageFromUpload(MachineImageFormat format, InputStream imageStream) throws CloudException, InternalException {
        throw new OperationNotSupportedException("Not yet");
    }

    @Override
    public boolean isImageSharedWithPublic(String machineImageId) throws CloudException, InternalException {
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
    public Iterable<MachineImage> listMachineImages() throws CloudException, InternalException {
        return listMachineImages(provider.getOrg(), false);
    }
    
    private Iterable<MachineImage> listMachineImages(AdminOrg org, boolean published) throws CloudException, InternalException {
        RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx = provider.getCloudClient();
        
        try {
            try {
                Set<Reference> refs = org.getCatalogs();
                
                if( refs == null ) {
                    return Collections.emptyList();
                }
                ArrayList<MachineImage> images = new ArrayList<MachineImage>();

                for( Reference type : refs ) {
                    Catalog c = ctx.getApi().getCatalogClient().getCatalog(type.getHref());
                    
                    if( c != null && (c.isPublished() == published) ) {
                        for( Reference itemType : c.getCatalogItems() ) {
                            CatalogItem item = ctx.getApi().getCatalogClient().getCatalogItem(itemType.getHref());
                            
                            if( item.getEntity().getType().equals(VCloudDirectorMediaType.VAPP_TEMPLATE) ) {
                                try {
                                    VAppTemplate template = ctx.getApi().getVAppTemplateClient().getVAppTemplate(item.getEntity().getHref());
                                    MachineImage image = toMachineImage(ctx, org, template);
                                    
                                    if( image != null ) {
                                        images.add(image);
                                    } 
                                }
                                catch( AuthorizationException ignore ) {
                                    // ignore
                                }
                           }
                        }
                    }
                }
                return images;
            }
            catch( RuntimeException e ) {
                logger.error("Error looking up images in " + provider.getContext().getRegionId() + ": " + e.getMessage());
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
    public Iterable<MachineImage> listMachineImagesOwnedBy(String accountId) throws CloudException, InternalException {
        if( accountId == null ) {
            //return listMachineImages(provider.getOrg(), true);
            return Collections.emptyList();
        }
        return listMachineImages(provider.getOrg(accountId), false);
    }

    @Override
    public Iterable<MachineImageFormat> listSupportedFormats() throws CloudException, InternalException {
        return Collections.singletonList(MachineImageFormat.VMDK);
    }

    @Override
    public Iterable<String> listShares(String forMachineImageId) throws CloudException, InternalException {
        return Collections.emptyList();
    }

    @Override
    public @Nonnull String[] mapServiceAction(@Nonnull ServiceAction action) {
        return new String[0];
    }

    @Override
    public String registerMachineImage(String atStorageLocation) throws CloudException, InternalException {
        throw new OperationNotSupportedException("Not supported");
    }

    @Override
    public void remove(String machineImageId) throws CloudException, InternalException {
        RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx = provider.getCloudClient();
        
        try {
            try {
                VAppTemplate template = ctx.getApi().getVAppTemplateClient().getVAppTemplate(provider.toHref(ctx, machineImageId));
                boolean busy = true;
                
                while( busy ) {
                    try { Thread.sleep(1500L); }
                    catch( InterruptedException e ) { }
                    template = ctx.getApi().getVAppTemplateClient().getVAppTemplate(provider.toHref(ctx, machineImageId));
                    busy = false;
                    for( Task task : template.getTasks() ) {
                        if( task.getStatus().equals(Task.Status.QUEUED) || task.getStatus().equals(Task.Status.RUNNING) ) {
                            busy = true;
                        }
                    }
                }
                provider.waitForTask(ctx.getApi().getVAppTemplateClient().deleteVappTemplate(template.getHref()));
            }
            catch( RuntimeException e ) {
                logger.error("Error deleting " + machineImageId + ": " + e.getMessage());
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
    public Iterable<MachineImage> searchMachineImages(String keyword, Platform platform, Architecture architecture) throws CloudException, InternalException {
        if( !architecture.equals(Architecture.I64) ) {
            return Collections.emptyList();
        }
        ArrayList<MachineImage> results = new ArrayList<MachineImage>();
        
        for( MachineImage image : listMachineImages() ) {
            if( keyword != null ) {
                if( !image.getProviderMachineImageId().contains(keyword) && !image.getName().contains(keyword) && !image.getDescription().contains(keyword) ) {
                    continue;
                }
            }
            if( platform != null ) {
                Platform p = image.getPlatform();
                
                if( !platform.equals(p) ) {
                    if( platform.isWindows() ) {
                        if( !p.isWindows() ) {
                            continue;
                        }
                    }
                    else if( platform.equals(Platform.UNIX) ){
                        if( !p.isUnix() ) {
                            continue;
                        }
                    }
                    else {
                        continue;
                    }
                }
            }
            results.add(image);
        }
        return results;
    }

    @Override
    public void shareMachineImage(String machineImageId, String withAccountId, boolean allow) throws CloudException, InternalException {
        throw new OperationNotSupportedException("Cannot share templates");
    }

    @Override
    public boolean supportsCustomImages() {
        return true;
    }

    @Override
    public boolean supportsImageSharing() {
        return false;
    }

    @Override
    public boolean supportsImageSharingWithPublic() {
        return false;
    }

    @Override
    public String transfer(CloudProvider fromCloud, String machineImageId) throws CloudException, InternalException {
        throw new OperationNotSupportedException("Not supported");
    }

    private MachineImage toMachineImage(RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx, Org org, VAppTemplate template) {
        if( template == null) {
            return null;
        }
        MachineImage image = new MachineImage();
        
        image.setProviderMachineImageId(provider.toId(ctx, template.getHref()));
        image.setName(template.getName());
        if( image.getName() == null ) {
            image.setName(image.getProviderMachineImageId());
        }
        image.setDescription(template.getDescription());
        if( image.getDescription() == null ) {
            image.setDescription(image.getName());
        }
        image.setProviderOwnerId(org.getName());
        image.setProviderRegionId(provider.getContext().getRegionId());
        image.setType(MachineImageType.VOLUME);
        image.setArchitecture(getArchitecture(template));
        image.setSoftware("");
        image.setTags(new HashMap<String,String>());
        
        image.setCurrentState(MachineImageState.ACTIVE);
        image.setPlatform(getPlatform(template));
        return image;
    }
    
    public Architecture getArchitecture(VAppTemplate template) {
        String str = template.getName() + " " + template.getDescription();
        
        if( str.contains("amd64") || str.contains("64-bit") ) {
            return Architecture.I64;
        }
        else {
            return Architecture.I32;
        }
    }
    
    public Platform getPlatform(VAppTemplate template) {
        String osType = null;
        
        OperatingSystemSection osSec = getSection(template, OperatingSystemSection.class);
        if( osSec != null ) {
            osType = osSec.getOsType();
        }
        if( osType == null ) {
            osType = template.getName() + " " + template.getDescription();
        }
        return Platform.guess(osType);
    }

    public static <S extends SectionType> S getSection(VAppTemplate template, Class<S> sectionClass) {
        S section = (S) Iterables.find(template.getSections(), Predicates.instanceOf(sectionClass));
        return section;
    }
}
