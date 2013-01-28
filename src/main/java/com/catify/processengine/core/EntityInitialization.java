/**
 * *******************************************************
 * Copyright (C) 2013 catify <info@catify.com>
 * *******************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.catify.processengine.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.bind.JAXBElement;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.data.neo4j.support.Neo4jTemplate;
import org.springframework.transaction.annotation.Transactional;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;

import com.catify.processengine.core.data.model.entities.ArchiveNode;
import com.catify.processengine.core.data.model.entities.ClientNode;
import com.catify.processengine.core.data.model.entities.FlowNode;
import com.catify.processengine.core.data.model.entities.FlowNodeFactory;
import com.catify.processengine.core.data.model.entities.ProcessInstanceNode;
import com.catify.processengine.core.data.model.entities.ProcessNode;
import com.catify.processengine.core.data.model.entities.RootNode;
import com.catify.processengine.core.data.model.entities.RunningNode;
import com.catify.processengine.core.data.services.ArchivedNodeRepositoryService;
import com.catify.processengine.core.data.services.ClientNodeRepositoryService;
import com.catify.processengine.core.data.services.FlowNodeRepositoryService;
import com.catify.processengine.core.data.services.ProcessRepositoryService;
import com.catify.processengine.core.data.services.RootNodeRepositoryService;
import com.catify.processengine.core.data.services.RunningNodeRepositoryService;
import com.catify.processengine.core.data.services.impl.IdService;
import com.catify.processengine.core.nodes.NodeFactory;
import com.catify.processengine.core.nodes.ServiceNodeBridge;
import com.catify.processengine.core.processdefinition.jaxb.TFlowElement;
import com.catify.processengine.core.processdefinition.jaxb.TFlowNode;
import com.catify.processengine.core.processdefinition.jaxb.TProcess;
import com.catify.processengine.core.processdefinition.jaxb.TSequenceFlow;
import com.catify.processengine.core.processdefinition.jaxb.TSubProcess;
import com.catify.processengine.core.processdefinition.jaxb.services.ExtensionService;
import com.catify.processengine.core.services.ActorReferenceService;

/**
 * EntityInitialization creates the data representation of a bpmn process and the runtime logic service node actors. <p>
 * It will create the basic node entities ({@link ClientNode}, {@link ArchiveNode}, {@link RunningNode}) and the process specific node entities ({@link ProcessNode}, 
 * {@link ProcessInstanceNode} and {@link FlowNode}) in the database via the repository services. <p>
 * The service node actor implementation is handled by the {@link NodeFactory}.
 * 
 * @author chris
 * 
 */
@Configurable
public class EntityInitialization {

	static final Logger LOG = LoggerFactory
			.getLogger(EntityInitialization.class);

	/** The actor system. */
	@Autowired
	private ActorSystem actorSystem;
	
	/** The neo4j template. */
	@Autowired
	private Neo4jTemplate neo4jTemplate;

	/** The root node repository service. */
	@Autowired
	private RootNodeRepositoryService rootNodeRepositoryService;

	/** The client node repository service. */
	@Autowired
	private ClientNodeRepositoryService clientNodeRepositoryService;

	/** The process repository service. */
	@Autowired
	private ProcessRepositoryService processRepositoryService;

	/** The flow node repository service. */
	@Autowired
	private FlowNodeRepositoryService flowNodeRepositoryService;

	/** The archived node repository service. */
	@Autowired
	private ArchivedNodeRepositoryService archivedNodeRepositoryService;

	/** The running node repository service. */
	@Autowired
	private RunningNodeRepositoryService runningNodeRepositoryService;


	/**
	 * Initialize neo4j data beans.
	 *
	 * @param clientId the client id
	 * @param processJaxb the process object generated by jaxb
	 */
	@Transactional
	public synchronized void initializeProcess(String clientId, TProcess processJaxb) {

		List<TFlowNode> flowNodesJaxb = new ArrayList<TFlowNode>();
		List<TSequenceFlow> sequenceFlowsJaxb = new ArrayList<TSequenceFlow>();

		// iterate through process elements and separate flow nodes and
		// sequenceFlows (because they need to be activated after each other)
		for (JAXBElement<? extends TFlowElement> flowElementJaxb : processJaxb
				.getFlowElement()) {

			LOG.debug(String.format("Instantiating %s:%s as a neo4j data node",
					flowElementJaxb.getDeclaredType().getSimpleName(),
					flowElementJaxb.getValue().getId()));

			if (flowElementJaxb.getDeclaredType().equals(TSequenceFlow.class)) {
				sequenceFlowsJaxb.add((TSequenceFlow) flowElementJaxb
						.getValue());
			} else if (flowElementJaxb.getValue() instanceof TFlowNode) {
				flowNodesJaxb.add((TFlowNode) flowElementJaxb.getValue());
			}
		}

		// Save the process to the neo4j db or retrieve it if it already exists
		this.createEntities(clientId, processJaxb, flowNodesJaxb,
				sequenceFlowsJaxb);
	}
	
	/**
	 * Save jaxb objects to the database.
	 * 
	 * @param processJaxb
	 *            the process generated by jaxb
	 * @param flowNodesJaxb
	 *            the list of jaxb flow nodes in that process
	 * @param sequenceFlowsJaxb
	 *            the list of jaxb sequence flows in that process
	 */
	private void createEntities(String clientId, TProcess processJaxb,
			List<TFlowNode> flowNodesJaxb, List<TSequenceFlow> sequenceFlowsJaxb) {

		// create the client context
		ClientNode clientNode = this.createClientContext(clientId);

		// create the running process node or get it from the db (eg. restart of the process engine)
		ProcessNode runningProcess = this.createRunningProcessNode(clientId, clientNode, processJaxb);
		
		// create the archived process node or get it from the db (eg. restart of the process engine)
		ProcessNode archivedProcess = this.createArchivedProcessNode(clientId, clientNode, processJaxb);

		// create the flow nodes (database and runtime)
		this.createFlowNodes(clientId, processJaxb, new ArrayList<TSubProcess>(), flowNodesJaxb, sequenceFlowsJaxb, runningProcess,
				archivedProcess);

		// persist process nodes
		processRepositoryService.save(runningProcess);
		processRepositoryService.save(archivedProcess);
		
		// save changes made to this client
		clientNodeRepositoryService.save(clientNode);
	}
	
	/**
	 * Creates the flow nodes (database) and service nodes (runtime) of a process. 
	 *
	 * @param clientId the client id
	 * @param processJaxb the process jaxb
	 * @param flowNodesJaxb the flow nodes jaxb
	 * @param sequenceFlowsJaxb the sequence flows jaxb
	 * @param runningParentNode the neo4j process
	 * @param runningPath true, if working the running process path, false if working on the archive process path
	 */
	private void createFlowNodes(String clientId, TProcess processJaxb, ArrayList<TSubProcess> subProcessesJaxb, List<TFlowNode> flowNodesJaxb, List<TSequenceFlow> sequenceFlowsJaxb,
			Object runningParentNode, Object archiveParentNode) {

		// map between jaxb flow elements and neo4j flow nodes (to be able to later connect these nodes)
		Map<TFlowNode, FlowNode> jaxbToNeo4jRunningProcess = new HashMap<TFlowNode, FlowNode>();
		Map<TFlowNode, FlowNode> jaxbToNeo4jArchiveProcess = new HashMap<TFlowNode, FlowNode>();

		// create the top level flow nodes and connect them to the process
		for (TFlowNode flowNodeJaxb : flowNodesJaxb) {

			// create running nodes (database)
			FlowNode runningFlowNode = createRunningFlowNode(clientId, processJaxb, flowNodeJaxb, subProcessesJaxb, runningParentNode);
			jaxbToNeo4jRunningProcess.put(flowNodeJaxb, runningFlowNode);

			// create archive nodes (database)
			FlowNode archiveFlowNode = createArchiveFlowNode(clientId, processJaxb, flowNodeJaxb, subProcessesJaxb, archiveParentNode);
			jaxbToNeo4jArchiveProcess.put(flowNodeJaxb, archiveFlowNode);
	
			// create service nodes (runtime)
			createNodeServiceActor(clientId, processJaxb, subProcessesJaxb, flowNodeJaxb, sequenceFlowsJaxb);
			
			// create the sub process flow nodes and connect them to their parent nodes
			if (flowNodeJaxb instanceof TSubProcess) {
				
				// We need an ordered list of sub processes, because there again could be nested sub 
				// processes in sub processes. We therefore need to recursively resolve them. 
				// For this a new variable needs to be created for every recursive call which is 
				// then filled with the list of previous sub processes.
				ArrayList<TSubProcess> recursiveSubProcessesJaxb = new ArrayList<TSubProcess>(subProcessesJaxb);
				recursiveSubProcessesJaxb.add((TSubProcess) flowNodeJaxb);
				
				this.createSubProcessNodes(clientId, processJaxb, recursiveSubProcessesJaxb, runningFlowNode, archiveFlowNode);
			}
		}

		// create sequence flows between the flow nodes
		for (TSequenceFlow sequenceFlowJaxb : sequenceFlowsJaxb) {
			this.connectFlowNodes(sequenceFlowJaxb, jaxbToNeo4jRunningProcess);
			this.connectFlowNodes(sequenceFlowJaxb, jaxbToNeo4jArchiveProcess);
		}
		
		// persist sub process flow nodes
		if (runningParentNode instanceof FlowNode && archiveParentNode instanceof FlowNode) {
			flowNodeRepositoryService.save((FlowNode) runningParentNode);
			flowNodeRepositoryService.save((FlowNode) archiveParentNode);
		}
	}
	
	/**
	 * Creates a sub process. 
	 * <p>
	 * Can be called recursively to create nested sub processes.
	 *
	 * @param clientId the client id
	 * @param processJaxb the process jaxb
	 * @param subProcessesJaxb the sub processes jaxb
	 * @param parentNodeRunning the parent node
	 * @param runningPath true, if working the running process path, false if working on the archive process path
	 */
	private void createSubProcessNodes(String clientId, TProcess processJaxb, 
			ArrayList<TSubProcess> subProcessesJaxb, FlowNode parentNodeRunning, FlowNode parentNodeArchive) {
		
		List<TFlowNode> flowNodesJaxb = new ArrayList<TFlowNode>();
		List<TSequenceFlow> sequenceFlowsJaxb = new ArrayList<TSequenceFlow>();
						
		// only check the (currently) last iteration of the list sub processes
		if (subProcessesJaxb.size() > 0 ) {
			TSubProcess subProcessJaxb = subProcessesJaxb.get(subProcessesJaxb.size()-1);
			
			for (JAXBElement<? extends TFlowElement> flowElementJaxb : subProcessJaxb.getFlowElement()) {
				
				if (flowElementJaxb.getDeclaredType().equals(TSequenceFlow.class)) {
					sequenceFlowsJaxb.add((TSequenceFlow) flowElementJaxb
							.getValue());
				} else if (flowElementJaxb.getValue() instanceof TFlowNode) {
					flowNodesJaxb.add((TFlowNode) flowElementJaxb.getValue());
				}
			}
			
			// create the flow nodes that belong to this sub process
			this.createFlowNodes(clientId, processJaxb,
					subProcessesJaxb, flowNodesJaxb, sequenceFlowsJaxb, parentNodeRunning, parentNodeArchive);
		}

	}
	
	/**
	 * Creates the running flow node.
	 *
	 * @param clientId the client id
	 * @param processJaxb the process jaxb
	 * @param flowNodeJaxb the flow node jaxb
	 * @param subProcessesJaxb the sub processes jaxb
	 * @param runningParentNode the running parent node
	 * @return the flow node
	 */
	private FlowNode createRunningFlowNode(String clientId, TProcess processJaxb, TFlowNode flowNodeJaxb, 
			ArrayList<TSubProcess> subProcessesJaxb, Object runningParentNode) {

		// create running flow node
		FlowNode runningFlowNode = flowNodeRepositoryService.getOrCreateFlowNode(
				FlowNodeFactory.createFlowNode(flowNodeJaxb, 
						IdService.getUniqueFlowNodeId(clientId, processJaxb, subProcessesJaxb, flowNodeJaxb)));
		
		LOG.debug(String.format("Added %s as %s with grapId: %s to neo4j db (running node)",
				flowNodeJaxb.getName(), runningFlowNode, runningFlowNode.getGraphId()));
		
		// depending on the type of the parent node, add a relationship between the new flow node and its parent
		if (runningParentNode instanceof ProcessNode) {
			((ProcessNode) runningParentNode).addRelationshipToFlowNode(runningFlowNode);		
		} else if (runningParentNode instanceof FlowNode) {
			((FlowNode) runningParentNode).addRelationshipToSubProcessNode(runningFlowNode);
		}
		
		return runningFlowNode;
	}
	
	/**
	 * Creates the archive flow node.
	 *
	 * @param clientId the client id
	 * @param processJaxb the process jaxb
	 * @param flowNodeJaxb the flow node jaxb
	 * @param subProcessesJaxb the sub processes jaxb
	 * @param archiveParentNode the archive parent node
	 * @return the flow node
	 */
	private FlowNode createArchiveFlowNode(String clientId, TProcess processJaxb, TFlowNode flowNodeJaxb, 
			ArrayList<TSubProcess> subProcessesJaxb, Object archiveParentNode) {
		// create archive node
		FlowNode archiveFlowNode = flowNodeRepositoryService.getOrCreateFlowNode(
				FlowNodeFactory.createFlowNode(flowNodeJaxb, 
						IdService.ARCHIVEPREFIX + IdService.getUniqueFlowNodeId(clientId, processJaxb, subProcessesJaxb, flowNodeJaxb)));
		
		LOG.debug(String.format("Added %s as %s with grapId: %s to neo4j db (archive node)",
				flowNodeJaxb.getName(), archiveFlowNode, archiveFlowNode.getGraphId()));
		
		// depending on the type of the parent node, add a relationship between the new flow node and its parent
		if (archiveParentNode instanceof ProcessNode) {
			((ProcessNode) archiveParentNode).addRelationshipToFlowNode(archiveFlowNode);
		} else if (archiveParentNode instanceof FlowNode) {
			((FlowNode) archiveParentNode).addRelationshipToSubProcessNode(archiveFlowNode);
		}
		
		return archiveFlowNode;
	}

	/**
	 * Creates the client context.
	 * <p>
	 * Gets or creates the {@link RootNode} and {@link ClientNode} and creates a
	 * relationship to the given process node.
	 * 
	 * @param clientId
	 *            the client id
	 * @return the client node
	 */
	private ClientNode createClientContext(String clientId) {

		// FIXME: provide RootNode implementation
		// create root node or get it from the db
		RootNode rootNode = createRootContext();
		
		// create client node or get it from the db
		ClientNode clientNode = clientNodeRepositoryService
				.getOrCreateClientNode(clientId);

		LOG.debug(String.format("Added %s with grapId: %s to neo4j db",
				clientNode, clientNode.getGraphId()));

		// create relationship between root and client
		rootNode.addRelationshipToClientNode(clientNode);
		rootNodeRepositoryService.save(rootNode);

		return clientNode;
	}

	/**
	 * Creates the root context.
	 *
	 * @return the root node
	 */
	private RootNode createRootContext() {
		RootNode rootNode = rootNodeRepositoryService
				.getOrCreateRootNode("secretRootId");

		LOG.debug(String.format("Added %s with grapId: %s to neo4j db",
				rootNode, rootNode.getGraphId()));
		
		RelationshipType referenceNodeRelationship = new RelationshipType() {
			
			@Override
			public String name() {
				return "REFERENCE_NODE";
			}
		};

		Node n = neo4jTemplate.getPersistentState(rootNode);
		neo4jTemplate.getReferenceNode().createRelationshipTo(n, referenceNodeRelationship);

		rootNodeRepositoryService.save(rootNode);
		return rootNode;
	}
	
	/**
	 * Creates the running process node.
	 *
	 * @param clientId the client id
	 * @param clientNode the client node
	 * @param processJaxb the process jaxb
	 * @return the process node
	 */
	private ProcessNode createRunningProcessNode(String clientId, ClientNode clientNode, TProcess processJaxb) {
		// create the running node
		RunningNode runningNode = runningNodeRepositoryService
				.getOrCreateRunningNode(clientId);
		clientNode.addRelationshipToRunningProcessNode(runningNode);

		clientNodeRepositoryService.save(clientNode);
		
		LOG.debug(String.format("Added %s with grapId: %s to neo4j db",
				runningNode, runningNode.getGraphId()));
		
		// create the running process node or get it from the db (eg. restart of
		// the process engine)
		ProcessNode runningProcess = processRepositoryService
				.getOrCreateProcessNode(new ProcessNode(
						IdService.getUniqueProcessId(clientId, processJaxb), 
						processJaxb.getId(),
						processJaxb.getName(), 
						ExtensionService.getTVersion(processJaxb).getVersion()));
		
		runningNode.addRelationshipToProcessNode(runningProcess);
		runningNodeRepositoryService.save(runningNode);
		
		LOG.debug(String.format("Added %s with grapId: %s to neo4j db",
				runningProcess, runningProcess.getGraphId()));
		
		return runningProcess;
	}
	
	/**
	 * Creates the archived process node.
	 *
	 * @param clientId the client id
	 * @param clientNode the client node
	 * @param processJaxb the process jaxb
	 * @return the process node
	 */
	private ProcessNode createArchivedProcessNode(String clientId, ClientNode clientNode, TProcess processJaxb) {
		// create the archive node
		ArchiveNode archiveNode = archivedNodeRepositoryService
				.getOrCreateArchivedNode(clientId);
		clientNode.addRelationshipToArchivedProcessNode(archiveNode);
		
		LOG.debug(String.format("Added %s with grapId: %s to neo4j db",
				archiveNode, archiveNode.getGraphId()));
		
		// create the archived process node or get it from the db (eg. restart of
		// the process engine)
		ProcessNode archivedProcess = processRepositoryService
				.getOrCreateProcessNode(new ProcessNode(
						IdService.ARCHIVEPREFIX + IdService.getUniqueProcessId(clientId, processJaxb), 
						processJaxb.getId(),
						processJaxb.getName(), 
						ExtensionService.getTVersion(processJaxb).getVersion()));
		
		archiveNode.addRelationshipToProcessNode(archivedProcess);
		archivedNodeRepositoryService.save(archiveNode);
		
		LOG.debug(String.format("Added %s with grapId: %s to neo4j db",
				archivedProcess, archivedProcess.getGraphId()));
		
		return archivedProcess;
	}
	
	/**
	 * Connect flow nodes via sequence flows.
	 * 
	 * @param sequenceFlowJaxb
	 *            the jaxb sequence flow holding the source and target to
	 *            connect
	 * @param jaxbToNeo4j
	 *            the map that maps jaxb objects to their corresponding neo4j
	 *            node objects
	 */
	private void connectFlowNodes(TSequenceFlow sequenceFlowJaxb,
			Map<TFlowNode, FlowNode> jaxbToNeo4j) {

		FlowNode sourceNode = jaxbToNeo4j.get(sequenceFlowJaxb.getSourceRef());
		FlowNode targetNode = jaxbToNeo4j.get(sequenceFlowJaxb.getTargetRef());

		sourceNode.addFollowingFlowNodes(neo4jTemplate, targetNode);

		LOG.debug(String.format("Connecting %s:%s and %s:%s in neo4j db",
				sourceNode.getClass().getSimpleName(), sourceNode
						.getUniqueFlowNodeId(), targetNode.getClass()
						.getSimpleName(), targetNode.getUniqueFlowNodeId()));
	}
	
	/**
	 * Creates the node service actor (runtime representation of a flow node).
	 *
	 * @param clientId the client id
	 * @param processJaxb the process jaxb
	 * @param subProcessesJaxb the sub processes jaxb
	 * @param flowNodeJaxb the flow node jaxb
	 * @param sequenceFlowsJaxb the sequence flows jaxb
	 * @return the actor reference
	 */
	private ActorRef createNodeServiceActor(String clientId,
			TProcess processJaxb, ArrayList<TSubProcess> subProcessesJaxb, TFlowNode flowNodeJaxb, 
			 List<TSequenceFlow> sequenceFlowsJaxb) {
		
		// create flow node actors (a bridge factory is used to be able to pass parameters to the UntypedActorFactory)
		ActorRef nodeServiceActor = this.actorSystem.actorOf(new Props(
				new ServiceNodeBridge(clientId, processJaxb, subProcessesJaxb, flowNodeJaxb, sequenceFlowsJaxb)
					).withDispatcher("file-mailbox-dispatcher"), ActorReferenceService.getActorReferenceString(
						IdService.getUniqueFlowNodeId(clientId, processJaxb, subProcessesJaxb, flowNodeJaxb)));
		
		LOG.debug(String.format("%s --> resulting akka object: %s", flowNodeJaxb,
				nodeServiceActor.toString()));
		return nodeServiceActor;
	}

}
