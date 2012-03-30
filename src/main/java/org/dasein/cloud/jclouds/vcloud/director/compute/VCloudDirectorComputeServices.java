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

import javax.annotation.Nonnull;

import org.dasein.cloud.compute.AbstractComputeServices;
import org.dasein.cloud.jclouds.vcloud.director.VCloudDirector;

public class VCloudDirectorComputeServices extends AbstractComputeServices {
    private VCloudDirector provider;
    
    public VCloudDirectorComputeServices(@Nonnull VCloudDirector provider) { this.provider = provider; }
        
    @Override
    public @Nonnull VAppTemplateSupport getImageSupport() {
        return new VAppTemplateSupport(provider);
    }
    
    @Override
    public @Nonnull VmSupport getVirtualMachineSupport() {
        return new VmSupport(provider);
    }
}
