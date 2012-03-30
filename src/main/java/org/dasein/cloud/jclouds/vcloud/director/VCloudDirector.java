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

package org.dasein.cloud.jclouds.vcloud.director;

import static org.jclouds.concurrent.MoreExecutors.sameThreadExecutor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Properties;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.log4j.Logger;
import org.dasein.cloud.AbstractCloud;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.jclouds.vcloud.director.compute.VCloudDirectorComputeServices;
import org.dasein.cloud.jclouds.vcloud.director.network.VCloudDirectorNetworkServices;
import org.dasein.cloud.storage.BlobStoreSupport;
import org.dasein.cloud.storage.StorageServices;
import org.jclouds.Constants;
import org.jclouds.compute.ComputeServiceContextFactory;
import org.jclouds.concurrent.config.ExecutorServiceModule;
import org.jclouds.logging.log4j.config.Log4JLoggingModule;
import org.jclouds.rest.RestContext;
import org.jclouds.vcloud.director.v1_5.VCloudDirectorAsyncClient;
import org.jclouds.vcloud.director.v1_5.VCloudDirectorClient;
import org.jclouds.vcloud.director.v1_5.domain.AdminOrg;
import org.jclouds.vcloud.director.v1_5.domain.Reference;
import org.jclouds.vcloud.director.v1_5.domain.Task;
import org.jclouds.vcloud.director.v1_5.domain.VApp;
import org.jclouds.vcloud.director.v1_5.domain.Vm;
import org.jclouds.vcloud.director.v1_5.predicates.ReferencePredicates;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

public class VCloudDirector extends AbstractCloud {
    static private final Logger logger = Logger.getLogger(VCloudDirector.class);
    
    public VCloudDirector() { }
    
    @Override
    public @Nonnull String getCloudName() {
        ProviderContext ctx = getContext();
        
        if( ctx == null ) {
            return "vCloud Director";
        }
        String name = ctx.getCloudName();
        
        return (name == null ? "vCloud Director" : name);
    }

    @Override
    public @Nonnull String getProviderName() {
        ProviderContext ctx = getContext();

        if( ctx == null ) {
            return "VMware";
        }
        String name = ctx.getCloudName();
        
        return (name == null ? "VMware" : name);
    }

    public @Nonnull RestContext<VCloudDirectorClient,VCloudDirectorAsyncClient> getCloudClient() throws CloudException {
        ProviderContext ctx = getContext();
        
        if( ctx == null ) {
            throw new CloudException("No context was set for this request");
        }
        ComputeServiceContextFactory factory = new ComputeServiceContextFactory();
        String user = new String(ctx.getAccessPublic());
        String key = new String(ctx.getAccessPrivate());
        String endpoint = ctx.getEndpoint();
        Properties overrides = new Properties();     
        
        user = user + "@" + ctx.getAccountNumber();
        overrides.setProperty(Constants.PROPERTY_TRUST_ALL_CERTS, "true");
        overrides.setProperty(Constants.PROPERTY_RELAX_HOSTNAME, "true");
        if( endpoint != null && !endpoint.trim().equals("") ) {
            overrides.setProperty("vcloud.endpoint", endpoint);
        }
        return factory.createContext("vcloud", user, key, ImmutableSet.of(new Log4JLoggingModule(), new ExecutorServiceModule(sameThreadExecutor(), sameThreadExecutor())), overrides).getProviderSpecificContext();
    }
    
    @Override
    public @Nonnull VCloudDirectorComputeServices getComputeServices() {
        return new VCloudDirectorComputeServices(this);
    }
    
    @Override
    public @Nonnull VCloudDirectorDataCenterServices getDataCenterServices() {
        return new VCloudDirectorDataCenterServices(this);
    }
    
    @Override
   public @Nonnull VCloudDirectorNetworkServices getNetworkServices() {
        return new VCloudDirectorNetworkServices(this);
    }
    
    private transient volatile AdminOrg currentOrg;
    
    public @Nonnull AdminOrg getOrg() throws CloudException {
        ProviderContext ctx = getContext();
        
        if( ctx == null ) {
            throw new CloudException("No context was established for this request");
        }
        if( currentOrg == null ) {
            currentOrg = getOrg(ctx.getAccountNumber());
        }
        return currentOrg;
    }
    
    public @Nonnull AdminOrg getOrg(URI href) throws CloudException {
        RestContext<VCloudDirectorClient, VCloudDirectorAsyncClient> ctx = getCloudClient();
        
        try {
            return ctx.getApi().getAdminOrgClient().getOrg(href);
        }
        finally {
            ctx.close();
        }        
    }
    
    public @Nonnull AdminOrg getOrg(String name) throws CloudException {
        RestContext<VCloudDirectorClient, VCloudDirectorAsyncClient> ctx = getCloudClient();
        
        try {
            Reference orgRef = Iterables.find(
                 ctx.getApi().getOrgClient().getOrgList().getOrgs(),
                 ReferencePredicates.nameEquals(name));
            return getOrg(orgRef.getHref());
        }
        finally {
            ctx.close();
        }
    }
    
    @Override
    public @Nullable String testContext() {
        try {
            ProviderContext providerContext = getContext();
                     
            if( providerContext == null ) {
                return null;
            }
            RestContext<VCloudDirectorClient, VCloudDirectorAsyncClient> ctx = getCloudClient();

            try {
                ctx.getApi().getOrgClient().getOrgList();
            }
            finally {
                ctx.close();
            }            
            if( hasStorageServices() ) {
                // test the storage cloud if connected to one
                StorageServices services = getStorageServices();
                
                if( services != null && services.hasBlobStoreSupport() ) {
                    BlobStoreSupport support = services.getBlobStoreSupport();
                    
                    if( support != null && !support.isSubscribed() ) {
                        return null;
                    }
                }
            }
            return providerContext.getAccountNumber(); // TODO: implement this properly
        }
        catch( Throwable t ) {
            logger.warn("Failed to test vCloud context: " + t.getMessage());
            if( logger.isDebugEnabled() ) {
                t.printStackTrace();
            }
            return null;
        }
    }
    
    public @Nonnull URI toHref(RestContext<VCloudDirectorClient, VCloudDirectorAsyncClient> ctx, String id) {
        try {
            return new URI(ctx.getEndpoint() + "/v" + ctx.getApiVersion() + id);
        }
        catch( URISyntaxException e ) {
            throw new RuntimeException(e);
        }
    }
    
    public @Nonnull String toId(@Nonnull RestContext<VCloudDirectorClient, VCloudDirectorAsyncClient> ctx, @Nonnull URI uri) {
        String endpoint = ctx.getEndpoint().toASCIIString();
        String str = uri.toASCIIString();
        int extra;
        
        if( (str.startsWith("http:") && endpoint.startsWith("http:")) || (str.startsWith("https:") && endpoint.startsWith("https:")) ) {
            extra = 0;
        }
        else if( str.startsWith("https:") && endpoint.startsWith("http:") ) {
            extra = 1;
        }
        else if( str.startsWith("http:") && endpoint.startsWith("https") ) {
            extra = -1;
        }
        else {
            throw new RuntimeException("Unknown protocol endpoints " + str + " against " + endpoint);
        }
        int idx = ((ctx.getEndpoint() + "/v" + ctx.getApiVersion()).length()) + extra;
        
        return str.substring(idx);
    }

    
    public @Nullable Vm waitForIdle(@Nonnull RestContext<VCloudDirectorClient, VCloudDirectorAsyncClient> ctx, @Nullable Vm vm) {
        if( vm == null ) {
            return null;
        }
        boolean busy = true;
        final URI vmUri = vm.getHref();
        while( busy ) {
            try { Thread.sleep(1500L); }
            catch( InterruptedException ignore ) { /* ignore */ }
            vm = Iterables.find(ctx.getApi().getVAppClient().getVApp(vm.getVAppParent().getHref()).getChildren().getVms(),
	                  new Predicate<Vm>() {
			               @Override
			               public boolean apply(Vm input) {
			                  return vmUri.equals(input.getHref());
			               }
			            });
            if( vm == null ) {
                return null;
            }
            busy = false;
            for( Task task : vm.getTasks() ) {
                busy = false;
                if( task == null || task.getStatus() == null ) {
                    continue;
                }
                if( task.getStatus().equals(Task.Status.QUEUED) || task.getStatus().equals(Task.Status.RUNNING) ) {
                    busy = true;
                }
            }
        }    
        return vm;
    }
    
    public @Nullable VApp waitForIdle(@Nonnull RestContext<VCloudDirectorClient, VCloudDirectorAsyncClient> ctx, @Nullable VApp vapp) {
        if( vapp == null ) {
            return null;
        }
        boolean busy = true;
        
        while( busy ) {
            try { Thread.sleep(1500L); }
            catch( InterruptedException ignore ) { }
            vapp = ctx.getApi().getVAppClient().getVApp(vapp.getHref());
            if( vapp == null ) {
                return null;
            }
            busy = false;
            for( Task task : vapp.getTasks() ) {
                busy = false;
                if( task == null || task.getStatus() == null ) {
                    continue;
                }
                if( task.getStatus().equals(Task.Status.QUEUED) || task.getStatus().equals(Task.Status.RUNNING) ) {
                    busy = true;
                }
            }
        }    
        return vapp;
    }
    
    public void waitForTask(@Nonnull Task task) throws CloudException {
        while( task != null && (task.getStatus().equals(Task.Status.RUNNING) || task.getStatus().equals(Task.Status.QUEUED)) ) {
            try { Thread.sleep(5000L); }
            catch( InterruptedException ignore ) { }
            RestContext<VCloudDirectorClient, VCloudDirectorAsyncClient> ctx = getCloudClient();
            
            try {
                try {
                    task = ctx.getApi().getTaskClient().getTask(task.getHref());
                }
                catch( RuntimeException e ) {
                    logger.warn("Error looking up task: " + e.getMessage());
                }
            }
            finally {
                ctx.close();
            }
        }
        if( task != null ) {
            if( task.getStatus().equals(Task.Status.ERROR) ) {
                throw new CloudException(task.getError().getMessage());
            }
        }        
    }
    
    public @Nonnull String validateName(@Nonnull String s) {
        StringBuilder str = new StringBuilder();
        
        s = s.toLowerCase();
        for( int i=0; i<s.length(); i++ ) {
            char c = s.charAt(i);
            
            if( str.length() < 1 ) {
                if( Character.isLetter(c) ) {
                    str.append(c);
                }
            }
            else {
                if( Character.isLetterOrDigit(c) ) {
                    str.append(c);
                }
                else if( c == '-' ) {
                    str.append(c);
                }
                else if( c == ' ' ) {
                    str.append('-');
                }
            }
        }
        if( str.length() < 1 ) {
            str.append("unnamed");
        }
        if( str.length() > 13 ) {
            return str.substring(0, 13);
        }
        return str.toString();
    }
}

