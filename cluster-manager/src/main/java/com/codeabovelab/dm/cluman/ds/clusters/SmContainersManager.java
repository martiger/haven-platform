/*
 * Copyright 2017 Code Above Lab LLC
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

package com.codeabovelab.dm.cluman.ds.clusters;

import com.codeabovelab.dm.cluman.cluster.docker.management.DockerService;
import com.codeabovelab.dm.cluman.cluster.docker.management.argument.GetServicesArg;
import com.codeabovelab.dm.cluman.cluster.docker.management.argument.GetTasksArg;
import com.codeabovelab.dm.cluman.cluster.docker.model.swarm.ContainerSpec;
import com.codeabovelab.dm.cluman.cluster.docker.model.swarm.Endpoint;
import com.codeabovelab.dm.cluman.cluster.docker.model.swarm.Service;
import com.codeabovelab.dm.cluman.cluster.docker.model.swarm.Task;
import com.codeabovelab.dm.cluman.model.*;
import com.codeabovelab.dm.common.utils.StringUtils;
import com.codeabovelab.dm.common.utils.TimeUtils;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;

import java.util.*;

/**
 * Containers manager for swarm-mode clusters.
 */
@Slf4j
class SmContainersManager implements ContainersManager {
    public static final Joiner JOINER = Joiner.on(' ');
    protected final DockerCluster dc;

    SmContainersManager(DockerCluster dc) {
        this.dc = dc;
    }

    protected DockerService getDocker() {
        DockerService service = this.dc.getDocker();
        Assert.notNull(service, "Cluster return null docker value");
        return service;
    }


    @Override
    public Collection<ContainerService> getServices() {
        List<Service> services = getDocker().getServices(new GetServicesArg());
        ImmutableList.Builder<ContainerService> ilb = ImmutableList.builder();
        services.forEach((s) -> {
            ContainerService.Builder csb = ContainerService.builder();
            csb.setCluster(dc.getName());
            csb.setCreated(s.getCreated());
            csb.setUpdated(s.getUpdated());
            csb.setId(s.getId());
            Service.ServiceSpec spec = s.getSpec();
            csb.setLabels(spec.getLabels());
            csb.setName(spec.getName());
            Task.TaskSpec task = spec.getTaskTemplate();
            ContainerSpec container = task.getContainer();
            String image = container.getImage();
            ImageName im = ImageName.parse(image);
            csb.setImage(im.getFullName());
            csb.setImageId(im.getId());
            csb.setCommand(container.getCommand());
            convertPorts(s.getEndpoint().getPorts(), csb.getPorts());
            ilb.add(csb.build());
        });
        return ilb.build();
    }

    @Override
    public Collection<DockerContainer> getContainers() {
        List<Task> tasks = getDocker().getTasks(new GetTasksArg());
        ImmutableList.Builder<DockerContainer> builder = ImmutableList.builder();
        Map<String, NodeInfo> nodes = new HashMap<>();
        dc.getNodes().forEach(ni -> {
            String id = ni.getIdInCluster();
            if(id != null) {
                nodes.put(id, ni);
            }
        });
        tasks.forEach((t) -> {
            try {
                NodeInfo ni = nodes.get(t.getNodeId());
                DockerContainer dc = fromTask(ni, t);
                builder.add(dc);
            } catch (Exception e) {
                // full task.toString() too big for log
                log.error("On {}", t.getId(), e);
            }
        });
        return builder.build();
    }

    private DockerContainer fromTask(NodeInfo ni, Task task) {
        DockerContainer.Builder dcb = DockerContainer.builder();
        ContainerSpec container = task.getSpec().getContainer();
        String image = container.getImage();
        ImageName im = ImageName.parse(image);
        dcb.setImage(im.getFullName());
        dcb.setImageId(im.getId());
        dcb.setName(task.getName());
        dcb.setId(task.getId());
        dcb.setNode(ni);
        dcb.setLabels(task.getLabels());
        List<String> command = container.getCommand();
        if(command != null) {
            dcb.setCommand(JOINER.join(command));
        }
        dcb.setCreated(TimeUtils.toMillis(task.getCreated()));
        Task.TaskStatus status = task.getStatus();
        Task.PortStatus portStatus = status.getPortStatus();
        if(portStatus != null) {
            convertPorts(portStatus.getPorts(), dcb.getPorts());
        }
        dcb.setState(convertState(status.getState()));
        dcb.setStatus(MoreObjects.firstNonNull(status.getError(), status.getMessage()));
        return dcb.build();
    }

    private void convertPorts(List<Endpoint.PortConfig> ports, List<Port> target) {
        if(ports == null) {
            return;
        }
        ports.forEach(pc -> {
            target.add(new Port(pc.getTargetPort(), pc.getPublishedPort(), pc.getProtocol()));
        });
    }

    private DockerContainer.State convertState(Task.TaskState state) {
        switch (state) {
            case NEW:
            case ALLOCATED:
            case PENDING:
            case ASSIGNED:
            case ACCEPTED:
            case PREPARING:
            case READY:
            case STARTING:
                return DockerContainer.State.CREATED;
            case RUNNING:
                return DockerContainer.State.RUNNING;
            case COMPLETE:
            case SHUTDOWN:
                return DockerContainer.State.EXITED;
            case FAILED:
            case REJECTED:
                return DockerContainer.State.DEAD;
        }
        return null;
    }
}