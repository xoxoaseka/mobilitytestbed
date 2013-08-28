package cz.agents.agentpolis.darptestbed.siminfrastructure.logger.analyser;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.eventbus.Subscribe;

import cz.agents.agentpolis.darptestbed.siminfrastructure.logger.analyser.init.TestbedProcessor;
import cz.agents.agentpolis.darptestbed.siminfrastructure.logger.analyser.structure.AvgMaxProcessor;
import cz.agents.agentpolis.darptestbed.siminfrastructure.logger.item.AlgRealTimeLogItem;
import cz.agents.agentpolis.darptestbed.siminfrastructure.logger.item.PassengerGetInVehicleLogItem;
import cz.agents.agentpolis.darptestbed.siminfrastructure.logger.item.PassengerGetOffVehicleLogItem;
import cz.agents.agentpolis.darptestbed.siminfrastructure.logger.item.PassengerRequestLogItem;
import cz.agents.agentpolis.siminfrastructure.logger.agent.activity.EndDrivingLogItem;
import cz.agents.agentpolis.siminfrastructure.logger.agent.activity.MovementArrivalLogItem;
import cz.agents.agentpolis.siminfrastructure.logger.agent.activity.StartDrivingLogItem;
import cz.agents.agentpolis.utils.InitAndGetterUtil;
import eu.superhub.wp4.analyser.processor.data.AvgCounter;
import eu.superhub.wp4.analyser.structure.VehiclePath;

public class TestbedLogAnalyser {

	private static final Logger LOGGER = Logger.getLogger(TestbedLogAnalyser.class);
	private final File resultOutputFile;
	private final long simulationStartTime;

	// String == vehicle id
	private final Map<String, VehiclePath> vehiclePaths = new HashMap<String, VehiclePath>();

	private final List<TestbedProcessor> testbedProcessors = new ArrayList<TestbedProcessor>();
	private final AvgMaxProcessor passengerOnBoardTime = new AvgMaxProcessor();
	private final AvgMaxProcessor passengerWaitTime = new AvgMaxProcessor();
	private final AvgMaxProcessor passengerTravelTime = new AvgMaxProcessor();
	private final Multiset<VehicleHourKey> productivity = HashMultiset.create();
	private long algRealTime = 0;

	public TestbedLogAnalyser(File result) {
		this(result, System.currentTimeMillis());
	}

	public TestbedLogAnalyser(File result, long simulationStartTime) {
		super();
		this.resultOutputFile = result;
		this.simulationStartTime = simulationStartTime;
	}

	@Subscribe
	public void processPassengerRequest(PassengerRequestLogItem passengerRequest) {
		passengerWaitTime.addStartLogItem(passengerRequest.passengerId,
				passengerRequest.timeWindow.getEarliestDeparture());
		passengerTravelTime.addStartLogItem(passengerRequest.passengerId,
				passengerRequest.timeWindow.getEarliestDeparture());
	}

	@Subscribe
	public void processPassengerGetInVehicle(PassengerGetInVehicleLogItem passengerGetInVehicle) {
		productivity.add(VehicleHourKey.newinstance(passengerGetInVehicle.vehicleId,
				passengerGetInVehicle.simulationTime));
		passengerWaitTime.addEndLogItem(passengerGetInVehicle.passengerId, passengerGetInVehicle.simulationTime);
		passengerOnBoardTime.addStartLogItem(passengerGetInVehicle.passengerId, passengerGetInVehicle.simulationTime);
	}

	@Subscribe
	public void processPassengerGetOffVehicle(PassengerGetOffVehicleLogItem passengerGetOffVehicle) {
		passengerOnBoardTime.addEndLogItem(passengerGetOffVehicle.passengerId, passengerGetOffVehicle.simulationTime);
		passengerTravelTime.addEndLogItem(passengerGetOffVehicle.passengerId, passengerGetOffVehicle.simulationTime);
	}

	@Subscribe
	public void handleStartDrivingLogItem(StartDrivingLogItem startDrivingLogItem) {
		assert vehiclePaths.containsKey(startDrivingLogItem.driverId) == false : "Driver is draving ";

		VehiclePath vehiclePath = new VehiclePath(startDrivingLogItem.vehicleId, startDrivingLogItem.simulationTime);
		vehiclePaths.put(startDrivingLogItem.driverId, vehiclePath);

	}

	@Subscribe
	public void hadnleEndDrivingLogItem(EndDrivingLogItem endDrivingLogItem) {
		assert vehiclePaths.containsKey(endDrivingLogItem.driverId) : "Driver is not draving";

		VehiclePath vehiclePath = vehiclePaths.remove(endDrivingLogItem.driverId);

		if (vehiclePath.path.size() > 0) {

			for (TestbedProcessor vehiclePathProcessor : testbedProcessors) {
				vehiclePathProcessor.process(vehiclePath);
			}
		}

	}

	@Subscribe
	public void hadnleAlgRealTimeLogItem(AlgRealTimeLogItem algRealTimeLogItem) {
		this.algRealTime += algRealTimeLogItem.realTime;
	}

	public void addVehiclePathProcessor(TestbedProcessor testbedProcessor) {
		testbedProcessors.add(testbedProcessor);
	}

	@Subscribe
	public void hadnleMovementArrivalLogItem(MovementArrivalLogItem movementArrivalLogItem) {
		VehiclePath vehiclePath = vehiclePaths.get(movementArrivalLogItem.agentId);

		if (vehiclePath != null) {
			vehiclePath.addPath(movementArrivalLogItem);
			vehiclePaths.put(movementArrivalLogItem.agentId, vehiclePath);
		} else {

		}

	}

	public void processResult() {

		StringBuilder resultOutput = new StringBuilder();
		resultOutput.append(System.lineSeparator());
		appendWithNewLine(resultOutput, "------ Simulation result -----------");
		appendWithNewLine(resultOutput, "Average passenger travel time (on-board) is :%s",
				parseTime(passengerTravelTime.getAvg()));
		appendWithNewLine(resultOutput, "Max passenger travel time (on-board) is :%s",
				parseTime(passengerTravelTime.getMax()));
		appendWithNewLine(resultOutput, "Median passenger travel time (on-board) is :%s",
				parseTime(passengerTravelTime.getMedian()));
		appendWithNewLine(resultOutput, "Average passenger ride time (on-board) is :%s",
				parseTime(passengerOnBoardTime.getAvg()));
		appendWithNewLine(resultOutput, "Max passenger ride time (on-board) is :%s",
				parseTime(passengerOnBoardTime.getMax()));
		appendWithNewLine(resultOutput, "Median passenger ride time (on-board) is :%s",
				parseTime(passengerOnBoardTime.getMedian()));
		appendWithNewLine(resultOutput, "Average passenger wait time is :%s", parseTime(passengerWaitTime.getAvg()));
		appendWithNewLine(resultOutput, "Max passenger wait time is :%s", parseTime(passengerWaitTime.getMax()));
		appendWithNewLine(resultOutput, "Median passenger wait time is :%s", parseTime(passengerWaitTime.getMedian()));

		for (TestbedProcessor testbedProcessor : testbedProcessors) {
			resultOutput.append(testbedProcessor.provideResult());
			resultOutput.append(System.lineSeparator());
		}

		appendWithNewLine(resultOutput, "Alg. real time :%s", parseTime(this.algRealTime));
		appendWithNewLine(resultOutput, "Simulation real time :%s", parseTime(System.currentTimeMillis()
				- simulationStartTime));
		resultOutput.append(printProductivity());

		LOGGER.info(resultOutput.toString());
		writeResultToFile(resultOutput.toString());
	}

	private void appendWithNewLine(StringBuilder resultOutput, String text, Object... args) {
		resultOutput.append(String.format(text, args));
		resultOutput.append(System.lineSeparator());
	}

	private void writeResultToFile(String resultOutput) {

		try (FileWriter fileWriter = new FileWriter(resultOutputFile)) {
			fileWriter.write(resultOutput);
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	private static final DateTimeFormatter fmt = DateTimeFormat.forPattern("HH:mm:ss");

	private String parseTime(double time) {
		return parseTime((long) time);
	}

	private String parseTime(long time) {
		if (time <= 0) {
			return "NaN";
		}
		return fmt.print(new DateTime(1999, 1, 2, 0, 0, 0).plus(time));
	}

	private String printProductivity() {
		SortedMap<Long, AvgCounter> avgCounters = computeAvgProductivity();
		if (avgCounters.isEmpty()) {
			return "";
		}

		StringBuilder output = new StringBuilder();
		String pattern = "Hour - %s : avg. passenger per vehicle hour - %s";

		for (long key = 0; key <= avgCounters.lastKey(); key++) {
			AvgCounter avgPassengerPerVehicleHour = avgCounters.get(key);
			if (avgPassengerPerVehicleHour == null) {
				output.append(String.format(pattern, key, 0));
			} else {
				output.append(String.format(pattern, key, avgPassengerPerVehicleHour.getCurrentAvgValue()));
			}

			output.append(System.lineSeparator());
		}

		return output.toString();

	}

	private SortedMap<Long, AvgCounter> computeAvgProductivity() {
		SortedMap<Long, AvgCounter> avgCounters = new TreeMap<>();
		for (VehicleHourKey vehicleHourKey : productivity) {
			AvgCounter avgCounter = InitAndGetterUtil.getDataOrInitFromMap(avgCounters, vehicleHourKey.hour,
					new AvgCounter());
			avgCounter.addValue(productivity.count(vehicleHourKey));
			avgCounters.put(vehicleHourKey.hour, avgCounter);
		}

		return avgCounters;

	}

	private static class VehicleHourKey {
		private final static long HOUR = Duration.standardHours(1).getMillis();

		public final String vehicleId;
		public final long hour;

		private VehicleHourKey(String vehicleId, long hour) {
			super();
			this.vehicleId = vehicleId;
			this.hour = hour;
		}

		public static VehicleHourKey newinstance(String vehicleId, long simulationTime) {
			return new VehicleHourKey(vehicleId, (long) simulationTime / HOUR);
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + (int) (hour ^ (hour >>> 32));
			result = prime * result + ((vehicleId == null) ? 0 : vehicleId.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			VehicleHourKey other = (VehicleHourKey) obj;
			if (hour != other.hour)
				return false;
			if (vehicleId == null) {
				if (other.vehicleId != null)
					return false;
			} else if (!vehicleId.equals(other.vehicleId))
				return false;
			return true;
		}

	}
}
