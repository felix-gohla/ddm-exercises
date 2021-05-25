package de.hpi.ddm.actors;

import java.io.Serializable;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import akka.actor.AbstractLoggingActor;
import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.Terminated;
import de.hpi.ddm.actors.Worker.CrackChunkMessage;
import de.hpi.ddm.structures.BloomFilter;
import de.hpi.ddm.structures.WorkItem;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

public class Master extends AbstractLoggingActor {

	////////////////////////
	// Actor Construction //
	////////////////////////
	
	public static final String DEFAULT_NAME = "master";

	public static Props props(final ActorRef reader, final ActorRef collector, final BloomFilter welcomeData) {
		return Props.create(Master.class, () -> new Master(reader, collector, welcomeData));
	}

	public Master(final ActorRef reader, final ActorRef collector, final BloomFilter welcomeData) {
		this.reader = reader;
		this.collector = collector;
		this.workers = new ArrayList<>();
		this.largeMessageProxy = this.context().actorOf(LargeMessageProxy.props(), LargeMessageProxy.DEFAULT_NAME);
		this.welcomeData = welcomeData;
		this.workMap = new HashMap<>();
		this.lineBuffer = new LinkedList<>();
	}

	////////////////////
	// Actor Messages //
	////////////////////

	@Data
	public static class StartMessage implements Serializable {
		private static final long serialVersionUID = -50374816448627600L;
	}
	
	@Data @NoArgsConstructor @AllArgsConstructor
	public static class BatchMessage implements Serializable {
		private static final long serialVersionUID = 8343040942748609598L;
		private List<String[]> lines;
	}

	@Data
	public static class RegistrationMessage implements Serializable {
		private static final long serialVersionUID = 3303081601659723997L;
	}
	
	/////////////////
	// Actor State //
	/////////////////

	private final ActorRef reader;
	private final ActorRef collector;
	private final List<ActorRef> workers;
	private final ActorRef largeMessageProxy;
	private final BloomFilter welcomeData;
	private final HashMap<ActorRef, List<WorkItem>> workMap;
	// Used when no workers are there to distribute work to.
	private final List<String[]> lineBuffer;

	private long startTime;
	
	/////////////////////
	// Actor Lifecycle //
	/////////////////////

	@Override
	public void preStart() {
		Reaper.watchWithDefaultReaper(this);
	}

	////////////////////
	// Actor Behavior //
	////////////////////

	@Override
	public Receive createReceive() {
		return receiveBuilder()
				.match(StartMessage.class, this::handle)
				.match(BatchMessage.class, this::handle)
				.match(Terminated.class, this::handle)
				.match(RegistrationMessage.class, this::handle)
				// TODO: Add further messages here to share work between Master and Worker actors
				.matchAny(object -> this.log().info("Received unknown message: \"{}\"", object.toString()))
				.build();
	}

	protected void handle(StartMessage message) {
		this.startTime = System.currentTimeMillis();
		
		this.reader.tell(new Reader.ReadMessage(), this.self());
	}
	
	protected void handle(BatchMessage message) {
		
		// TODO: This is where the task begins:
		// - The Master received the first batch of input records.
		// - To receive the next batch, we need to send another ReadMessage to the reader.
		// - If the received BatchMessage is empty, we have seen all data for this task.
		// - We need a clever protocol that forms sub-tasks from the seen records, distributes the tasks to the known workers and manages the results.
		//   -> Additional messages, maybe additional actors, code that solves the subtasks, ...
		//   -> The code in this handle function needs to be re-written.
		// - Once the entire processing is done, this.terminate() needs to be called.
		
		// Info: Why is the input file read in batches?
		// a) Latency hiding: The Reader is implemented such that it reads the next batch of data from disk while at the same time the requester of the current batch processes this batch.
		// b) Memory reduction: If the batches are processed sequentially, the memory consumption can be kept constant; if the entire input is read into main memory, the memory consumption scales at least linearly with the input size.
		// - It is your choice, how and if you want to make use of the batched inputs. Simply aggregate all batches in the Master and start the processing afterwards, if you wish.

		// TODO: Stop fetching lines from the Reader once an empty BatchMessage was received; we have seen all data then
		if (message.getLines().isEmpty()) {
			// this.terminate();
			return;
		}

		this.distributeWork(message.getLines());
		this.pushWork();
		
		// TODO: Send (partial) results to the Collector
		this.collector.tell(new Collector.CollectMessage("If I had results, this would be one."), this.self());
		
		// TODO: Fetch further lines from the Reader
		this.reader.tell(new Reader.ReadMessage(), this.self());
	}
	
	protected void terminate() {
		this.collector.tell(new Collector.PrintMessage(), this.self());
		
		this.reader.tell(PoisonPill.getInstance(), ActorRef.noSender());
		this.collector.tell(PoisonPill.getInstance(), ActorRef.noSender());
		
		for (ActorRef worker : this.workers) {
			this.context().unwatch(worker);
			worker.tell(PoisonPill.getInstance(), ActorRef.noSender());
		}
		
		this.self().tell(PoisonPill.getInstance(), ActorRef.noSender());
		
		long executionTime = System.currentTimeMillis() - this.startTime;
		this.log().info("Algorithm finished in {} ms", executionTime);
	}

	protected void handle(RegistrationMessage message) {
		this.context().watch(this.sender());
		this.workers.add(this.sender());
		this.log().info("Registered {}", this.sender());
		
		this.largeMessageProxy.tell(new LargeMessageProxy.LargeMessage<>(new Worker.WelcomeMessage(this.welcomeData), this.sender()), this.self());
		
		// TODO: Assign some work to registering workers. Note that the processing of the global task might have already started.
		this.reDistributeWork();
		this.pushWork();
	}
	
	protected void handle(Terminated message) {
		this.context().unwatch(message.getActor());
		this.workers.remove(message.getActor());
		this.log().info("Unregistered {}", message.getActor());
	}

	private void pushWork() {
		Iterator<Map.Entry<ActorRef, List<WorkItem>>> it = this.workMap.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<ActorRef, List<WorkItem>> entry = it.next();
			ActorRef worker = entry.getKey();
			long numWorkingItems = entry.getValue().stream()
				.filter((wi) -> wi.isWorking())
				.count();
			List<WorkItem> itemsToStart = entry.getValue().stream()
				.filter((wi) -> !wi.isWorking())
				// Ensure that there are maximum 5 items are cracking.
				.limit(Math.max(5 - numWorkingItems, 0))
				.collect(Collectors.toList());

			// Send chunks to the worker.
			List<String[]> lines = itemsToStart.stream()
				.map((wi) -> wi.getInputLine())
				.collect(Collectors.toList());

			if (lines.size() > 0) {
				CrackChunkMessage msg = new CrackChunkMessage(lines);
				this.log().debug("Sending chunk of " + lines.size() + " lines to " + worker + ".");
				this.largeMessageProxy.tell(new LargeMessageProxy.LargeMessage<>(msg, worker), this.self());
			}
			// Set chunks to working.
			itemsToStart.forEach((wi) -> wi.setWorking(true));
		}
	}

	private void distributeWork(List<String[]> newLines) {
		// Create all missing entries in the work map.
		for (ActorRef worker : this.workers) {
			this.workMap.putIfAbsent(worker, new LinkedList<WorkItem>());
		}

		if (this.workMap.size() < 1) {
			// Currently, there are no workes, so save that for later.
			lineBuffer.addAll(newLines);
			return;
		}

		// Idea: Chunk into equally sized parts to reduce overhead.
		//       Parsing is done by the workers in order to facilitate parallelism.		
		// Find the number of work items per work map entry.
		final List<Map.Entry<ActorRef, Integer>> entries = this.workMap.entrySet().stream()
			.map((e) -> new AbstractMap.SimpleEntry<ActorRef, Integer>(
				e.getKey(),
				e.getValue().size()
			))
			.sorted((i1, i2) -> i1.getValue().compareTo(i2.getValue()))
			.collect(Collectors.toList());

		// Try to distribute evenly.
		final Integer maxWorkItems = entries.get(entries.size() - 1).getValue();

		// Fill up buckets.
		for(Map.Entry<ActorRef, Integer> entry : entries) {
			final ActorRef ref = entry.getKey();
			List<WorkItem> items = this.workMap.get(ref);
			final int numberItemsToAdd = maxWorkItems - items.size();
			
			List<WorkItem> newItems = newLines.stream()
				.limit(numberItemsToAdd)
				.map(l -> new WorkItem(l))
				.collect(Collectors.toList());
			this.log().debug("Giving worker " + ref + " " + newItems.size() + " new lines.");

			newLines = newLines.subList(newItems.size(), newLines.size());
			items.addAll(newItems);
			this.workMap.put(ref, items);

			if (newLines.size() <= 0) {
				break;
			}
		}

		// Distribute the rest of lines (if any) evenly.
		final int itemsPerWorker = newLines.size() / entries.size();
		for (int workerIdx = 0; workerIdx < entries.size() && newLines.size() > 0; workerIdx++) {
			final ActorRef ref = entries.get(workerIdx).getKey();
			List<WorkItem> items = this.workMap.get(ref);

			// The last worker will get more items.
			final int numberItemsToAdd = workerIdx < entries.size() - 1
				? itemsPerWorker
				: newLines.size();

			List<WorkItem> newItems = newLines.stream()
				.limit(numberItemsToAdd)
				.map(line -> new WorkItem(line))
				.collect(Collectors.toList());
			this.log().debug("Giving worker " + ref + " " + newItems.size() + " new lines.");

			newLines = newLines.subList(newItems.size(), newLines.size());
			items.addAll(newItems);
			this.workMap.put(ref, items);
		}
		assert(newLines.size() == 0);
	}

	private void reDistributeWork() {
		// Create all missing entries in the work map.
		for (ActorRef worker : this.workers) {
			this.workMap.putIfAbsent(worker, new LinkedList<WorkItem>());
		}

		// Remove all unscheduled items.
		List<String[]> notStartedLines = new LinkedList<>();
		Iterator<Map.Entry<ActorRef, List<WorkItem>>> it = this.workMap.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<ActorRef, List<WorkItem>> entry = it.next();
			List<WorkItem> notStartedItemsForEntry = entry.getValue().stream()
				.filter((wi) -> !wi.isWorking())
				.collect(Collectors.toList());
			List<String[]> notStartedLinesForEntry = notStartedItemsForEntry.stream()
				.map((wi) -> wi.getInputLine())
				.collect(Collectors.toList());
			List<WorkItem> originalEntries = entry.getValue();
			originalEntries.removeAll(notStartedItemsForEntry);
			this.workMap.put(entry.getKey(), originalEntries);
			notStartedLines.addAll(notStartedLinesForEntry);
		}

		notStartedLines.addAll(this.lineBuffer);
		this.lineBuffer.clear();
		this.distributeWork(notStartedLines);
	}
}
