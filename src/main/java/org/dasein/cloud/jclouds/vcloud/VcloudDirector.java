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

package org.dasein.cloud.jclouds.vcloud;

import static org.jclouds.concurrent.MoreExecutors.sameThreadExecutor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Properties;

import org.apache.log4j.Logger;
import org.dasein.cloud.AbstractCloud;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.jclouds.vcloud.compute.VcloudComputeServices;
import org.dasein.cloud.jclouds.vcloud.network.VcloudNetworkServices;
import org.dasein.cloud.storage.BlobStoreSupport;
import org.dasein.cloud.storage.StorageServices;
import org.jclouds.Constants;
import org.jclouds.compute.ComputeServiceContextFactory;
import org.jclouds.concurrent.config.ExecutorServiceModule;
import org.jclouds.logging.log4j.config.Log4JLoggingModule;
import org.jclouds.rest.RestContext;
import org.jclouds.vcloud.VCloudAsyncClient;
import org.jclouds.vcloud.VCloudClient;
import org.jclouds.vcloud.domain.Org;
import org.jclouds.vcloud.domain.Task;
import org.jclouds.vcloud.domain.TaskStatus;
import org.jclouds.vcloud.domain.VApp;
import org.jclouds.vcloud.domain.Vm;

import com.google.common.collect.ImmutableSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class VcloudDirector extends AbstractCloud {
    static private final Logger logger = Logger.getLogger(VcloudDirector.class);
    
    public VcloudDirector() { }
    
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

    public @Nonnull RestContext<VCloudClient,VCloudAsyncClient> getCloudClient() throws CloudException {
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
    public @Nonnull VcloudComputeServices getComputeServices() {
        return new VcloudComputeServices(this);
    }
    
    @Override
    public @Nonnull VcloudVDC getDataCenterServices() {
        return new VcloudVDC(this);
    }
    
    public @Nonnull VcloudNetworkServices getNetworkServices() {
        return new VcloudNetworkServices(this);
    }
    
    private transient volatile Org currentOrg;
    
    public @Nonnull Org getOrg() throws CloudException {
        ProviderContext ctx = getContext();
        
        if( ctx == null ) {
            throw new CloudException("No context was established for this request");
        }
        if( currentOrg == null ) {
            currentOrg = getOrg(ctx.getAccountNumber());
        }
        return currentOrg;
    }
    
    public @Nonnull Org getOrg(URI href) throws CloudException {
        RestContext<VCloudClient, VCloudAsyncClient> ctx = getCloudClient();
        
        try {
            return ctx.getApi().getOrgClient().getOrg(href);
        }
        finally {
            ctx.close();
        }        
    }
    
    public @Nonnull Org getOrg(String name) throws CloudException {
        RestContext<VCloudClient, VCloudAsyncClient> ctx = getCloudClient();
        
        try {
            return ctx.getApi().getOrgClient().findOrgNamed(name);
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
            RestContext<VCloudClient, VCloudAsyncClient> ctx = getCloudClient();

            try {
                ctx.getApi().getOrgClient().listOrgs();
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
    
    public @Nonnull URI toHref(RestContext<VCloudClient, VCloudAsyncClient> ctx, String id) {
        try {
            return new URI(ctx.getEndpoint() + "/v" + ctx.getApiVersion() + id);
        }
        catch( URISyntaxException e ) {
            throw new RuntimeException(e);
        }
    }
    
    public @Nonnull String toId(@Nonnull RestContext<VCloudClient, VCloudAsyncClient> ctx, @Nonnull URI uri) {
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

    
    public @Nullable Vm waitForIdle(@Nonnull RestContext<VCloudClient, VCloudAsyncClient> ctx, @Nullable Vm vm) {
        if( vm == null ) {
            return null;
        }
        boolean busy = true;
        
        while( busy ) {
            try { Thread.sleep(1500L); }
            catch( InterruptedException ignore ) { /* ignore */ }
            vm = ctx.getApi().getVmClient().getVm(vm.getHref());
            if( vm == null ) {
                return null;
            }
            busy = false;
            for( Task task : vm.getTasks() ) {
                busy = false;
                if( task == null || task.getStatus() == null ) {
                    continue;
                }
                if( task.getStatus().equals(TaskStatus.QUEUED) || task.getStatus().equals(TaskStatus.RUNNING) ) {
                    busy = true;
                }
            }
        }    
        return vm;
    }
    
    public @Nullable VApp waitForIdle(@Nonnull RestContext<VCloudClient, VCloudAsyncClient> ctx, @Nullable VApp vapp) {
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
                if( task.getStatus().equals(TaskStatus.QUEUED) || task.getStatus().equals(TaskStatus.RUNNING) ) {
                    busy = true;
                }
            }
        }    
        return vapp;
    }
    
    public void waitForTask(@Nonnull Task task) throws CloudException {
        while( task != null && (task.getStatus().equals(TaskStatus.RUNNING) || task.getStatus().equals(TaskStatus.QUEUED)) ) {
            try { Thread.sleep(5000L); }
            catch( InterruptedException ignore ) { }
            RestContext<VCloudClient, VCloudAsyncClient> ctx = getCloudClient();
            
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
            if( task.getStatus().equals(TaskStatus.ERROR) ) {
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

