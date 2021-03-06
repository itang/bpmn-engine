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
package com.catify.processengine.core.nodes.eventdefinition;

import java.util.Set;

import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.util.Timeout;

import com.catify.processengine.core.messages.ActivationMessage;
import com.catify.processengine.core.messages.CommitMessage;
import com.catify.processengine.core.messages.DeactivationMessage;
import com.catify.processengine.core.messages.TriggerMessage;
import com.catify.processengine.core.nodes.NodeUtils;

/**
 * The TerminateEventDefinition end a  whole process instance (including all top level
 * and sub process flow node instances) by sending deactivation messages to them. It then
 * waits a configurable amount of time for the nodes to commit their deactivation.
 * 
 * @author christopher köster
 * 
 */
public class TerminateEventDefinition extends EventDefinition {

	private String uniqueProcessId;
	private String uniqueFlowNodeId;
	private ActorRef actorRef;
	
	/** The actor references of all other node services (including sub process nodes). */
	private Set<ActorRef> topLevelActorRefs;
	
	/**
	 * Instantiates a new terminate event definition.
	 *
	 * @param uniqueProcessId the unique process id
	 * @param uniqueFlowNodeId the unique flow node id
	 * @param actorRef the actor ref
	 */
	public TerminateEventDefinition(
			String uniqueProcessId, String uniqueFlowNodeId, ActorRef actorRef, Set<ActorRef> topLevelActorRefs) {
		super();
		this.uniqueProcessId = uniqueProcessId;
		this.uniqueFlowNodeId = uniqueFlowNodeId;
		this.actorRef = actorRef;
		this.topLevelActorRefs = topLevelActorRefs;
	}
	
	/* (non-Javadoc)
	 * @see com.catify.processengine.core.nodes.eventdefinition.EventDefinition#acitivate(com.catify.processengine.core.messages.ActivationMessage)
	 */
	@Override
	protected CommitMessage<?> activate(ActivationMessage message) {

		/** The timeout for collecting the deactivation commits (which is slightly shorter than the timeout 
		 * for this event definition, to be able to handle the timeout exception here) */
		Timeout deactivationTimeout = new Timeout(Duration.create((long) (timeoutInSeconds * 0.97), "seconds"));
		
		Future<Iterable<Object>> futureSequence = new NodeUtils().deactivateNodes(topLevelActorRefs, message.getProcessInstanceId(), deactivationTimeout, this.getSender(), this.getSelf());

	    // send commit to underlying event
		return createCommitMessage(futureSequence, message.getProcessInstanceId());
	}
	
	/* (non-Javadoc)
	 * @see com.catify.processengine.core.nodes.eventdefinition.EventDefinition#deactivate(com.catify.processengine.core.messages.DeactivationMessage)
	 */
	@Override
	protected CommitMessage<?> deactivate(DeactivationMessage message) {
		// nothing to do
		return createSuccessfullCommitMessage(message.getProcessInstanceId());
	}

	/* (non-Javadoc)
	 * @see com.catify.processengine.core.nodes.eventdefinition.EventDefinition#trigger(com.catify.processengine.core.messages.TriggerMessage)
	 */
	@Override
	protected CommitMessage<?> trigger(TriggerMessage message) {
		// nothing to do
		return createSuccessfullCommitMessage(message.getProcessInstanceId());
	}

	public String getUniqueProcessId() {
		return uniqueProcessId;
	}

	public void setUniqueProcessId(String uniqueProcessId) {
		this.uniqueProcessId = uniqueProcessId;
	}

	public String getUniqueFlowNodeId() {
		return uniqueFlowNodeId;
	}

	public void setUniqueFlowNodeId(String uniqueFlowNodeId) {
		this.uniqueFlowNodeId = uniqueFlowNodeId;
	}

	public ActorRef getActorRef() {
		return actorRef;
	}

	public void setActorRef(ActorRef actorRef) {
		this.actorRef = actorRef;
	}
}
