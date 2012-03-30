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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.dc.DataCenter;
import org.dasein.cloud.dc.DataCenterServices;
import org.dasein.cloud.dc.Region;
import org.jclouds.rest.RestContext;
import org.jclouds.vcloud.VCloudAsyncClient;
import org.jclouds.vcloud.VCloudClient;
import org.jclouds.vcloud.domain.ReferenceType;
import org.jclouds.vcloud.domain.VDC;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class VcloudVDC implements DataCenterServices {
    private VcloudDirector provider;
    
    VcloudVDC(@Nonnull VcloudDirector cloud) { provider = cloud; }

    private @Nonnull ProviderContext getContext() throws CloudException {
        ProviderContext ctx = provider.getContext();
        
        if( ctx == null ) {
            throw new CloudException("No context was defined for this request");
        }
        return ctx;
    } 
    
    @Override
    public @Nullable DataCenter getDataCenter(@Nonnull String providerDataCenterId) throws InternalException, CloudException {
        RestContext<VCloudClient, VCloudAsyncClient> ctx = provider.getCloudClient();

        try {
            return toDataCenter(ctx, getVDC(providerDataCenterId));
        }
        finally {
            ctx.close();
        }        
    }

    @Override
    public @Nonnull String getProviderTermForDataCenter(@Nonnull Locale locale) {
        return "VDC Unit";
    }

    @Override
    public @Nonnull String getProviderTermForRegion(@Nonnull Locale locale) {
        return "VDC";
    }

    @Override
    public @Nullable Region getRegion(@Nonnull String providerRegionId) throws InternalException, CloudException {
        for( Region region : listRegions() ) {
            if( providerRegionId.equals(region.getProviderRegionId()) ) {
                return region;
            }
        }
        return null;
    }

    public @Nullable VDC getVDC(@Nonnull String vdcId) throws CloudException {
        RestContext<VCloudClient, VCloudAsyncClient> ctx = provider.getCloudClient();

        try {
            return ctx.getApi().getVDCClient().getVDC(provider.toHref(ctx, vdcId));
        }
        finally {
            ctx.close();
        }
    }
    
    @Override
    public @Nonnull Collection<DataCenter> listDataCenters(@Nonnull String providerRegionId) throws InternalException, CloudException {
        if( !providerRegionId.equals(getContext().getRegionId()) ) {
            return Collections.emptyList();
        }
        RestContext<VCloudClient, VCloudAsyncClient> ctx = provider.getCloudClient();

        try {
            Map<String,ReferenceType> map = provider.getOrg().getVDCs();
            System.out.println("Data centers=" + map);
            ArrayList<DataCenter> dcs = new ArrayList<DataCenter>();
            
            if( map == null ) {
                return Collections.emptyList();
            }
            for( ReferenceType type : map.values() ) {
                VDC vdc = ctx.getApi().getVDCClient().getVDC(type.getHref());
                DataCenter dc = toDataCenter(ctx, vdc);
                
                if( dc != null ) {
                    dcs.add(dc);
                }
            }
            System.out.println("Result=" + dcs);
            return dcs;
        }
        finally {
            ctx.close();
        }
    }

    @Override
    public @Nonnull Collection<Region> listRegions() throws InternalException, CloudException {
        try {
            String endpoint = getContext().getEndpoint();
            
            if( endpoint == null ) {
                throw new CloudException("No endpoint was defined for region query");
            }
            return Collections.singletonList(toRegion(endpoint));
        }
        catch( URISyntaxException e ) {
            throw new InternalException("Improper endpoint: " + getContext().getEndpoint());
        }
    }
    
    private @Nonnull Region toRegion(@Nonnull String endpoint) throws URISyntaxException {
        URI uri = new URI(endpoint);
        Region region = new Region();
    
        region.setProviderRegionId(uri.getHost());
        region.setName(region.getProviderRegionId());
        region.setActive(true);
        region.setAvailable(true);
        return region;
    }
    
    private @Nullable DataCenter toDataCenter(@Nonnull RestContext<VCloudClient, VCloudAsyncClient> ctx, @Nullable VDC vdc) throws CloudException {
        if( vdc == null ) {
            return null;
        }
        DataCenter dc = new DataCenter();
        
        dc.setProviderDataCenterId(provider.toId(ctx, vdc.getHref()));
        dc.setActive(true);
        dc.setAvailable(true);
        dc.setName(vdc.getName());
        dc.setRegionId(getContext().getRegionId());
        return dc;        
    }

}
