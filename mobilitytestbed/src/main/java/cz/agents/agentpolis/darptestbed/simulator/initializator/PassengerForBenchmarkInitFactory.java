package cz.agents.agentpolis.darptestbed.simulator.initializator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import org.apache.log4j.Logger;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.joda.time.Duration;

import com.google.common.collect.Lists;
import com.google.inject.Injector;

import cz.agents.agentpolis.darptestbed.global.GlobalParams;
import cz.agents.agentpolis.darptestbed.global.Utils;
import cz.agents.agentpolis.darptestbed.siminfrastructure.communication.driver.protocol.DriverMessageProtocol;
import cz.agents.agentpolis.darptestbed.siminfrastructure.communication.requestconsumer.protocol.RequestConsumerMessageProtocol;
import cz.agents.agentpolis.darptestbed.siminfrastructure.logger.RequestLogger;
import cz.agents.agentpolis.darptestbed.siminfrastructure.request.GPS;
import cz.agents.agentpolis.darptestbed.siminfrastructure.request.Passenger;
import cz.agents.agentpolis.darptestbed.siminfrastructure.request.PassengerRequest;
import cz.agents.agentpolis.darptestbed.simmodel.agent.activity.movement.TestbedPassengerActivity;
import cz.agents.agentpolis.darptestbed.simmodel.agent.data.generator.BenchmarkRequestGenerator;
import cz.agents.agentpolis.darptestbed.simmodel.agent.data.generator.support.RequestBuilder;
import cz.agents.agentpolis.darptestbed.simmodel.agent.exception.WrongSettingsException;
import cz.agents.agentpolis.darptestbed.simmodel.agent.passenger.PassengerAgent;
import cz.agents.agentpolis.darptestbed.simmodel.agent.passenger.PassengerAgentFactory;
import cz.agents.agentpolis.darptestbed.simmodel.agent.passenger.PassengerDecentrAgent;
import cz.agents.agentpolis.darptestbed.simmodel.agent.passenger.PassengerProfile;
import cz.agents.agentpolis.darptestbed.simmodel.agent.passenger.logic.PassengerCentrLogic;
import cz.agents.agentpolis.darptestbed.simmodel.agent.passenger.logic.PassengerDecentrLogic;
import cz.agents.agentpolis.darptestbed.simmodel.agent.passenger.logic.PassengerDecentrLogicExample;
import cz.agents.agentpolis.darptestbed.simmodel.agent.passenger.logic.PassengerLogic;
import cz.agents.agentpolis.darptestbed.simmodel.agent.timer.Timer;
import cz.agents.agentpolis.darptestbed.simmodel.environment.model.TestbedModel;
import cz.agents.agentpolis.darptestbed.simulator.initializator.osm.NodeExtendedFunction;
import cz.agents.agentpolis.ondemandtransport.simulator.initializator.TaxiPassengerInit;
import cz.agents.agentpolis.siminfrastructure.time.TimeProvider;
import cz.agents.agentpolis.simmodel.agent.Agent;
import cz.agents.agentpolis.simmodel.agent.activity.TimeSpendingActivity;
import cz.agents.agentpolis.simmodel.environment.model.action.AgentPositionAction;
import cz.agents.agentpolis.simmodel.environment.model.query.AgentPositionQuery;
import cz.agents.agentpolis.simulator.creator.initializator.AgentInitFactory;

public class PassengerForBenchmarkInitFactory implements AgentInitFactory {

	private static final Logger logger = Logger.getLogger(TaxiPassengerInit.class);

	private final File serializedPassengerPopulation;

	private final int populationLimit;

	public PassengerForBenchmarkInitFactory(File serializedPassengerPopulation, int populationLimit) {
		super();
		this.serializedPassengerPopulation = serializedPassengerPopulation;
		this.populationLimit = populationLimit;
	}

	public PassengerForBenchmarkInitFactory(File serializedPassengerPopulation) {
		super();
		this.serializedPassengerPopulation = serializedPassengerPopulation;
		this.populationLimit = Integer.MAX_VALUE;
	}

	/**
	 * It is implementations method from IAgentInit.
	 */
	public List<Agent> initAllAgentLifeCycles(Injector injector) {

		List<Agent> agents = new ArrayList<Agent>();
		PassengerAgentFactory factory = new PassengerAgentFactory();
		Timer passTimer = injector.getInstance(TestbedModel.class).getPassengersTimer();
		// int randomBound = possibleNodes.size();
		boolean centralized = GlobalParams.isCentralized();

		// get ready for creating a logic (I couldn't figure out any better
		// place to hide this code)
		TestbedModel taxiModel = injector.getInstance(TestbedModel.class);
		AgentPositionQuery positionQuery = injector.getInstance(AgentPositionQuery.class);
		Utils utils = injector.getInstance(Utils.class);

		NodeExtendedFunction nearestNodeFinder = injector.getInstance(NodeExtendedFunction.class);
		TimeProvider timeProvider = injector.getInstance(TimeProvider.class);
		AgentPositionAction agentPositionAction = injector.getInstance(AgentPositionAction.class);

		List<Passenger> passenger = loadSerializedPassengerPopulation(serializedPassengerPopulation);
		Collections.shuffle(passenger, injector.getInstance(Random.class));

		int counter = 0;

		// create passengers
		for (Passenger agentRequests : passenger) {

			if (counter++ > populationLimit) {
				break;
			}

			// create agents and their logics
			String agentId = agentRequests.passsngerId;
			PassengerProfile profile = new PassengerProfile();
			TimeSpendingActivity timeActivity = injector.getInstance(TimeSpendingActivity.class);
			TestbedPassengerActivity passengerActivity = injector.getInstance(TestbedPassengerActivity.class);
			RequestLogger logger = injector.getInstance(RequestLogger.class);

			TimeSpendingActivity timeSpendingActivity = injector.getInstance(TimeSpendingActivity.class);

			LinkedList<RequestBuilder> requests = mapRequests(timeProvider, agentRequests.requests, nearestNodeFinder);
			BenchmarkRequestGenerator generator = new BenchmarkRequestGenerator(agentPositionAction, requests,
					timeProvider, timeSpendingActivity);

			RequestBuilder requestBuilder = requests.peek();

			Duration startLife = new Duration(requestBuilder
					.buildRequest(agentId, agentRequests.additionalRequirements).getTimeWindow().getEarliestDeparture());
			if (startLife.getMillis() < 1) {
				startLife = new Duration(1);
			}

			DriverMessageProtocol driverMessageProtocol = injector.getInstance(DriverMessageProtocol.class);

			PassengerAgent<? extends PassengerLogic<?>> passengerAgent = null;
			if (centralized) {
				// centralized algorithms

				RequestConsumerMessageProtocol sender = injector.getInstance(RequestConsumerMessageProtocol.class);

				// load the PassengerCentrLogic class for the passengers (in centralized case)
				PassengerCentrLogic logic = new PassengerCentrLogic(agentId, sender, driverMessageProtocol, taxiModel,
						positionQuery, utils, profile, passengerActivity, timeActivity, logger);
				passengerAgent = factory.createCentrAgent(agentId, logic, startLife,
						agentRequests.additionalRequirements, injector, generator);

			} else {
				PassengerDecentrLogic logic = null;
				RequestConsumerMessageProtocol sender = injector.getInstance(RequestConsumerMessageProtocol.class);

				// decentralized algorithms
				switch (GlobalParams.getDecentrAlgType()) {
				//case 1:
				//	break;
				//case 2:
				//	break;
				//case 3:
				//	break;
				default:
					// by default, load the PassengerDecentrLogicExample class for the passengers (decentr. case)
					logic = new PassengerDecentrLogicExample(agentId, sender, driverMessageProtocol,
							taxiModel, positionQuery, utils, profile, passengerActivity, timeActivity, logger);
				}

				PassengerDecentrAgent passengerDecentrAgent = factory.createDecentrAgent(agentId, logic, startLife,
						agentRequests.additionalRequirements, injector, generator);
				passTimer.addCallback(passengerDecentrAgent);

				passengerAgent = passengerDecentrAgent;

			}

			// chose home node
			injector.getInstance(TestbedModel.class).addPassenger(passengerAgent.getId());

			agents.add(passengerAgent);

		}

		logger.info("The number of created passengers: " + agents.size());
		return agents;

	}

	private LinkedList<RequestBuilder> mapRequests(TimeProvider timeProvider, List<PassengerRequest> loadedRequests,
			NodeExtendedFunction nearestNodeFinder) {

		LinkedList<RequestBuilder> result = Lists.newLinkedList();
		for (PassengerRequest request : loadedRequests) {
			long fromNode = findNearestNode(request.originPosition, nearestNodeFinder);
			long toNode = findNearestNode(request.destinationnPosition, nearestNodeFinder);
			result.add(new RequestBuilder(fromNode, toNode, request, request.reqestCallTimeInDayRange, timeProvider));

		}

		return result;

	}

	private long findNearestNode(GPS gps, NodeExtendedFunction nearestNodeFinder) {
		return nearestNodeFinder.getNearestNodeByNodeId(gps.longitude, gps.latitude);
	}

	private List<Passenger> loadSerializedPassengerPopulation(File serializedPopulation) {
		ObjectMapper mapper = new ObjectMapper();
		try {
			return mapper.readValue(serializedPopulation, new TypeReference<List<Passenger>>() {
			});
		} catch (IOException e) {
			logger.error(e);
		}

		return new ArrayList<Passenger>();

	}
}
