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

package org.dasein.cloud.jclouds.vcloud.director.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;

import org.apache.log4j.Logger;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.OperationNotSupportedException;
import org.dasein.cloud.identity.ServiceAction;
import org.dasein.cloud.jclouds.vcloud.director.VCloudDirector;
import org.dasein.cloud.network.NetworkInterface;
import org.dasein.cloud.network.Subnet;
import org.dasein.cloud.network.VLAN;
import org.dasein.cloud.network.VLANSupport;
import org.jclouds.rest.AuthorizationException;
import org.jclouds.rest.RestContext;
import org.jclouds.vcloud.director.v1_5.VCloudDirectorMediaType;
import org.jclouds.vcloud.director.v1_5.admin.VCloudDirectorAdminAsyncClient;
import org.jclouds.vcloud.director.v1_5.admin.VCloudDirectorAdminClient;
import org.jclouds.vcloud.director.v1_5.domain.AdminOrg;
import org.jclouds.vcloud.director.v1_5.domain.IpScope;
import org.jclouds.vcloud.director.v1_5.domain.Network;
import org.jclouds.vcloud.director.v1_5.domain.NetworkConnection;
import org.jclouds.vcloud.director.v1_5.domain.NetworkConnectionSection;
import org.jclouds.vcloud.director.v1_5.domain.OrgNetwork;
import org.jclouds.vcloud.director.v1_5.domain.Reference;
import org.jclouds.vcloud.director.v1_5.domain.Vm;

import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;

public class VCloudDirectorNetworkSupport implements VLANSupport {
    static private final Logger logger = Logger.getLogger(VCloudDirectorNetworkSupport.class);
    
    private VCloudDirector provider;
    
    VCloudDirectorNetworkSupport(VCloudDirector provider) { this.provider = provider; }
    
    @Override
    public boolean allowsNewVlanCreation() throws CloudException, InternalException {
        return false;
    }

    @Override
    public int getMaxVlanCount() throws CloudException, InternalException {
        return 0;
    }

    @Override
    public VLAN getVlan(String vlanId) throws CloudException, InternalException {
        for( VLAN vlan : listVlans() ) {
            if( vlan.getProviderVlanId().equals(vlanId) ) {
                return vlan;
            }
        }
        return null;
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        try {
            provider.getOrg().getNetworks();
            return true;
        }
        catch( AuthorizationException e ) {
            return false;
        }
        catch( RuntimeException e ) {
            throw new CloudException(e);
        }
    }

    @Override
    public Iterable<NetworkInterface> listNetworkInterfaces(String forVmId) throws CloudException, InternalException {
        RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx = provider.getCloudClient();
        
        try {
            try {
                Set<Reference> refs = provider.getOrg().getNetworks();
                ArrayList<NetworkInterface> list = new ArrayList<NetworkInterface>();
                ArrayList<OrgNetwork> networks = new ArrayList<OrgNetwork>();
                Vm vm = ctx.getApi().getVmClient().getVm(provider.toHref(ctx, forVmId));
                NetworkConnection def = null;

                if( refs != null ) {
                    for( Reference t : refs ) {
                        if( t.getType().equals(VCloudDirectorMediaType.NETWORK) ) {
                            Network network = ctx.getApi().getNetworkClient().getNetwork(t.getHref());
                            
                            if( network != null ) {
                                networks.add(network);
                            }
                        }
                    }
                }
                NetworkConnectionSection section = (NetworkConnectionSection) Iterables.find(vm.getSections(), Predicates.instanceOf(NetworkConnectionSection.class));
                for( NetworkConnection c : section.getNetworkConnections() ) {
                    NetworkInterface nic = new NetworkInterface();
                    
                    nic.setProviderNetworkInterfaceId(c.getMACAddress());
                    nic.setIpAddress(c.getIpAddress());
                    nic.setProviderVirtualMachineId(forVmId);
                    for( OrgNetwork network : networks ) {
                        if( network.getName().equals(c.getNetwork()) ) {
                            IpScope scope = network.getConfiguration().getIpScope();
                            
                            if( def == null || def.getNetworkConnectionIndex() > c.getNetworkConnectionIndex() ) {
                                def = c;
                            }
                            nic.setGatewayAddress(scope.getGateway());
                            nic.setNetmask(scope.getNetmask());
                            nic.setProviderVlanId(provider.toId(ctx, network.getHref()));
                        }
                    }
                }
                if( def != null ) {
                    for( NetworkInterface nic : list ) {
                        if( def.getMACAddress().equals(nic.getProviderNetworkInterfaceId()) ) {
                            nic.setDefaultRoute(true);
                        }
                    }
                }
                return list;
            }
            catch( RuntimeException e ) {
                logger.error("Error listing network interfaces for " + forVmId + ": " + e.getMessage());
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
    public Iterable<VLAN> listVlans() throws CloudException, InternalException {
        RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx = provider.getCloudClient();
        
        try {
            try {
                ArrayList<VLAN> list = new ArrayList<VLAN>();
                AdminOrg org = provider.getOrg();
                Set<Reference> refs = org.getNetworks();
                
                if( refs == null ) {
                    return Collections.emptyList();
                }
                for( Reference type : refs ) {
                    if( type.getType().equals(VCloudDirectorMediaType.NETWORK) ) {
                        Network network = ctx.getApi().getNetworkClient().getNetwork(type.getHref());
                        
                        VLAN vlan = toVlan(ctx, network);
                        
                        if( vlan != null ) {
                            list.add(vlan);
                        }
                    }
                }
                return list;
            }
            catch( RuntimeException e ) {
                logger.error("Error listing VLANs in " + provider.getContext().getRegionId() + ": " + e.getMessage());
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
    public void removeVlan(String vlanId) throws CloudException, InternalException {
        throw new OperationNotSupportedException("Network provisioning is not supported");
    }

    private String toCidr(String gateway, String netmask) {
        String[] dots = netmask.split("\\.");
        int cidr = 0;
        
        for( String item : dots ) {
            int x = Integer.parseInt(item);
            
            for( ; x > 0 ; x = (x<<1)%256 ) {
                cidr++;
            }
        }
        StringBuilder network = new StringBuilder();
        
        dots = gateway.split("\\.");
        int start = 0;
        
        for( String item : dots ) {
            if( ((start+8) < cidr) || cidr == 0 ) {
                network.append(item);
            }
            else {
                int addresses = (int)Math.pow(2, (start+8)-cidr);
                int subnets = 256/addresses;
                int gw = Integer.parseInt(item);
                
                for( int i=0; i<subnets; i++ ) {
                    int base = i*addresses;
                    int top = ((i+1)*addresses);
                    
                    if( gw >= base && gw < top ) {
                        network.append(String.valueOf(base));
                        break;
                    }
                }
            }
            start += 8;
            if( start < 32 ) {
                network.append(".");
            }
        }
        network.append("/");
        network.append(String.valueOf(cidr));
        return network.toString();
    }
    
    private VLAN toVlan(RestContext<VCloudDirectorAdminClient, VCloudDirectorAdminAsyncClient> ctx, Network network) throws CloudException {
        if( network == null ) {
            return null;
        }
        AdminOrg org = provider.getOrg(network.getOrg().getHref());
        VLAN vlan = new VLAN();

        vlan.setProviderOwnerId(org.getName());
        vlan.setProviderRegionId(provider.getContext().getRegionId());
        vlan.setProviderVlanId(provider.toId(ctx, network.getHref()));
        vlan.setName(network.getName());
        if( vlan.getName() == null ) {
            vlan.setName(vlan.getProviderVlanId());
        }
        vlan.setDescription(network.getDescription());
        if( vlan.getDescription() == null ) {
            vlan.setDescription(vlan.getName());
        }
        IpScope scope = network.getConfiguration().getIpScope();
        
        if( scope != null ) {
            String netmask = scope.getNetmask();
            String gateway = scope.getGateway();
            
            if( netmask != null && gateway != null ) {
                vlan.setCidr(toCidr(gateway, netmask));
            }
            vlan.setGateway(gateway);
            if( scope.getDns2() == null ) {
                if( scope.getDns1() == null ) {
                    vlan.setDnsServers(new String[0]);            
                }
                else {
                    vlan.setDnsServers(new String[] { scope.getDns1() });
                }
            }
            else if( scope.getDns1() == null ) {
                vlan.setDnsServers(new String[] { scope.getDns2() });
            }
            else {
                vlan.setDnsServers(new String[] { scope.getDns1(), scope.getDns2() });
            }
        }
        else {
            vlan.setDnsServers(new String[0]);            
        }
        return vlan;
    }

    @Override
    public boolean allowsNewSubnetCreation() throws CloudException, InternalException {
        return false;
    }

    @Override
    public Subnet createSubnet(String arg0, String arg1, String arg2, String arg3) throws CloudException, InternalException {
        throw new OperationNotSupportedException();
    }

    @Override
    public VLAN createVlan(String arg0, String arg1, String arg2, String arg3, String[] arg4, String[] arg5) throws CloudException, InternalException {
        throw new OperationNotSupportedException();
    }

    @Override
    public String getProviderTermForNetworkInterface(Locale locale) {
        return "network interface";
    }

    @Override
    public String getProviderTermForSubnet(Locale locale) {
        return "subnet";
    }

    @Override
    public String getProviderTermForVlan(Locale locale) {
        return "network";
    }

    @Override
    public Subnet getSubnet(String subnetId) throws CloudException, InternalException {
        return null;
    }

    @Override
    public boolean isVlanDataCenterConstrained() throws CloudException, InternalException {
        return false;
    }

    @Override
    public Iterable<Subnet> listSubnets(String networkId) throws CloudException, InternalException {
        return Collections.emptyList();
    }

    @Override
    public void removeSubnet(String subnetId) throws CloudException, InternalException {
        throw new OperationNotSupportedException("Subnets not supported with the vCloud API");
    }

    @Override
    public boolean isSubnetDataCenterConstrained() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean supportsVlansWithSubnets() throws CloudException, InternalException {
        return false;
    }
}
