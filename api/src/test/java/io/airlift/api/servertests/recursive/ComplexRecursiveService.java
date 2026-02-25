package io.airlift.api.servertests.recursive;

import io.airlift.api.ApiCreate;
import io.airlift.api.ApiService;
import io.airlift.api.ApiTrait;
import io.airlift.api.ServiceType;

@ApiService(type = ServiceType.class, name = "complex", description = "Has complex recursive poly resources")
public class ComplexRecursiveService
{
    @ApiCreate(description = "dummy", traits = ApiTrait.BETA)
    public void createLiveTable(PolyTest polyTest)
    {
        // NOP
    }
}
