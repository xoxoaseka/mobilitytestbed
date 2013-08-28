package cz.agents.agentpolis.darptestbed.simmodel.agent.passenger.logic;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Set;

import org.apache.log4j.Logger;

import cz.agents.agentpolis.darptestbed.global.Utils;
import cz.agents.agentpolis.darptestbed.siminfrastructure.communication.driver.message.PassengerIsInTaxiMessage;
import cz.agents.agentpolis.darptestbed.siminfrastructure.communication.driver.message.PassengerSaysTaxiIsTooLateMessage;
import cz.agents.agentpolis.darptestbed.siminfrastructure.communication.driver.protocol.DriverMessageProtocol;
import cz.agents.agentpolis.darptestbed.siminfrastructure.communication.passenger.message.Proposal;
import cz.agents.agentpolis.darptestbed.siminfrastructure.communication.passenger.message.RequestReject;
import cz.agents.agentpolis.darptestbed.siminfrastructure.logger.RequestLogger;
import cz.agents.agentpolis.darptestbed.simmodel.agent.AgentLogic;
import cz.agents.agentpolis.darptestbed.simmodel.agent.activity.movement.TestbedPassengerActivity;
import cz.agents.agentpolis.darptestbed.simmodel.agent.activity.movement.TestbedPassengerActivityCallback;
import cz.agents.agentpolis.darptestbed.simmodel.agent.data.Request;
import cz.agents.agentpolis.darptestbed.simmodel.agent.data.TimeWindow;
import cz.agents.agentpolis.darptestbed.simmodel.agent.data.TripInfo;
import cz.agents.agentpolis.darptestbed.simmodel.agent.passenger.PassengerProfile;
import cz.agents.agentpolis.darptestbed.simmodel.environment.model.TestbedModel;
import cz.agents.agentpolis.ondemandtransport.siminfrastructure.communication.protocol2.AMessageProtocol;
import cz.agents.agentpolis.simmodel.agent.activity.TimeSpendingActivity;
import cz.agents.agentpolis.simmodel.agent.activity.callback.TimeActivityCallback;
import cz.agents.agentpolis.simmodel.environment.model.query.AgentPositionQuery;

/**
 * This class contains all methods, that are common for both centralized and
 * decentralized version. Mainly, this applies for communication concerning
 * getting in a taxi.
 * 
 * @author Lukas Canda
 */
public abstract class PassengerLogic<TMessageProtocol extends AMessageProtocol<?>> extends AgentLogic<TMessageProtocol>
		implements TestbedPassengerActivityCallback, TimeActivityCallback {

	protected static final Logger LOGGER = Logger.getLogger(PassengerLogic.class);

	protected final String passengerId; // TODO: Agent takes this inforamtion
										// too

	/**
	 * Passenger's personal information and properties
	 */
	protected final PassengerProfile passengerProfile;
	/**
	 * Using this activity, the passenger can get in a vehicle and travel with
	 * it
	 */
	protected final TestbedPassengerActivity passengerActivity;
	/**
	 * Using this activity, the passenger can wait (for example for opening his
	 * time window)
	 */
	protected final TimeSpendingActivity timeSpendingActivity;
	/**
	 * Logging the requests sent into a file
	 */
	protected final RequestLogger logger;
	/**
	 * The id of the vehicle I've been assigned to for my current trip
	 */
	protected String currentVehicleId = null;
	/**
	 * The id of the taxi driver I've been assigned to for my current trip
	 */
	protected String currentDriverId = null;
	/**
	 * The request, that's been sent at the last time; it hasn't been assigned a
	 * taxi yet.
	 */
	private Set<String> passengerAdditionalRequirements;
	/**
	 * @return the passenger's current position node
	 */
	protected Long getCurrentPositionNode() {
		return this.positionQuery.getCurrentPositionByNodeId(this.passengerId);
	}

	protected Request requestLastSent = null;
	/**
	 * The request, that's been confirmed (and we're on the way or waiting for a
	 * taxi); it contains target node, time window etc.
	 */
	protected Request currentRequestConfirmed = null;

	private final DriverMessageProtocol driverMessageProtocol;

	public PassengerLogic(String agentId, TMessageProtocol sender, DriverMessageProtocol driverCentrMessageProtocol,
			TestbedModel taxiModel, AgentPositionQuery positionQuery, Utils utils, PassengerProfile passengerProfile,
			TestbedPassengerActivity passengerActivity, TimeSpendingActivity timeSpendingActivity, RequestLogger logger) {

		super(sender, taxiModel, positionQuery, utils);
		this.passengerId = agentId;
		this.passengerProfile = passengerProfile;
		this.passengerActivity = passengerActivity;
		this.timeSpendingActivity = timeSpendingActivity;
		this.logger = logger;
		this.driverMessageProtocol = driverCentrMessageProtocol;
	}

	/**
	 * This logic just saves the request. The request receiver is related to the
	 * type of communication (my implementation).
	 * 
	 * @param request
	 *            request to be send
	 */
	public void sendRequest(Request request) {
		this.requestLastSent = request;

		// log
		if (request.getTimeWindow() == null) {
			this.logger.logPassengerSentRequest(passengerId, request.getFromNode(), request.getToNode());
		} else {
			TimeWindow tw = request.getTimeWindow();
			this.logger.logPassengerSentRequest(passengerId, request.getFromNode(), request.getToNode(),
					tw.getEarliestDeparture(), tw.getLatestDeparture(), tw.getEarliestArrival(), tw.getLatestArrival());
		}

		// print out
		String printReq = "request " + passengerId + " from "
				+ this.positionQuery.getCurrentPositionByNodeId(passengerId) + " to " + request.getToNode();
		TimeWindow timeWin = request.getTimeWindow();
		if (timeWin != null) {
			printReq += " time win (" + utils.toHoursAndMinutes(timeWin.getEarliestDeparture()) + " "
					+ utils.toHoursAndMinutes(timeWin.getLatestArrival()) + ")";
		}

		//LOGGER.debug(printReq);
	}

	/**
	 * Here's some basic reaction on the taxi arrival - waiting until opening my
	 * time window. (Of course, it can be overridden if using different type of
	 * time windows.)
	 * 
	 * @param driverId
	 *            id of the arrived driver
	 * @param vehicleId
	 *            id of his vehicle
	 */
	public void processVehicleArrived(String driverId, String vehicleId) {
		// check out, if the arrived taxi is the one we've been waiting for
		if (vehicleId.equals(currentVehicleId) && driverId.equals(currentDriverId)) {

			if (this.currentRequestConfirmed.getTimeWindow() != null) {
				processVehicleArrivedWithTimeWindows(driverId, vehicleId);
			} else {
				processVehicleArrivedNoTimeWindows(driverId, vehicleId);
			}
			//LOGGER.debug("ON BOARD: "+taxiModel.getNumOfPassenOnBoard(vehicleId));
		}
	}

	/**
	 * Accepts the confirmation, that a taxi will pick me up.
	 * 
	 * @param vehicleId
	 *            the vehicle I should expect to pick me up
	 */
	public void acceptTripInfo(TripInfo tripInfo, Set<String> passengerAdditionalRequirements) {
		startWaiting(tripInfo.getDriverId(), tripInfo.getVehicleId(), passengerAdditionalRequirements);
	}

	/**
	 * Without checking time windows, the passenger just gets in and notifies
	 * the driver of it.
	 * 
	 * @param driverId
	 *            id of the arrived driver
	 * @param vehicleId
	 *            id of his vehicle
	 */
	private void processVehicleArrivedNoTimeWindows(String driverId, String vehicleId) {

		//LOGGER.info(passengerId + " got in at " + getCurrentTimeStr());
		passengerActivity.useArrivedVehicle(passengerId, vehicleId, currentRequestConfirmed.getToNode(), this);
		sendPassengerGotInVehicle(driverId, vehicleId);
	}

	/**
	 * If the driver arrived too early, the passenger just waits until his
	 * window opens and then gets in.
	 * 
	 * In case he arrived too late, he will notify him of it.
	 * 
	 * @param driverId
	 *            id of the arrived driver
	 * @param vehicleId
	 *            id of his vehicle
	 */
	private void processVehicleArrivedWithTimeWindows(String driverId, String vehicleId) {

		checkArgument(
				taxiModel.checkAdditionalRequirementsWithIncomingVehilce(vehicleId, passengerAdditionalRequirements),
				"The incoming vehicle with id " + vehicleId + ", doesn't satisfy passenger's requirements");

		long delay = this.currentRequestConfirmed.getTimeWindow().getDepartureDelay(utils.getCurrentTime());

		if (delay < 0) {
			// the taxi has arrived too early
			timeSpendingActivity.spendingTime(this, -delay);
		} else if (delay == 0) {
			// on time
			processVehicleArrivedNoTimeWindows(driverId, vehicleId);
		} else {
			// too late
			LOGGER.info(driverId + " arrived too late for " + passengerId + " (at " + getCurrentTimeStr() + ")");
			sendVehicleIsTooLate(driverId, true, delay);
		}
	}

	/**
	 * Notifies the driver of having gotten into the taxi
	 * 
	 * @param driverId
	 *            the driver of the vehicle
	 * @param vehicleId
	 *            the vehicle he got into
	 */
	protected void sendPassengerGotInVehicle(String driverId, String vehicleId) {

		driverMessageProtocol.sendMessage(driverId, new PassengerIsInTaxiMessage(passengerId, new TripInfo(driverId,
				vehicleId)));

	}

	/**
	 * Notifies the driver of having arrived too late (after the latest arrival
	 * time)
	 * 
	 * @param driverId
	 *            the driver - message receiver
	 * @param departure
	 *            true, if it was during departure, false if during arrival
	 * @param delay
	 *            the delay in milliseconds
	 */
	protected void sendVehicleIsTooLate(String driverId, boolean departure, long delay) {

		driverMessageProtocol.sendMessage(driverId,
				new PassengerSaysTaxiIsTooLateMessage(passengerId, departure, delay));

	}

	/**
	 * The request communication has ended and a taxi will pick me up. Now I wait.
	 * 
	 * @param driverId
	 *            the driver who'll pick me up
	 * @param vehicleId
	 *            the driver's vehicle
	 */
	protected void startWaiting(String driverId, String vehicleId, Set<String> passengerAdditionalRequirements) {
		logger.logRequestConfirmed(passengerId, driverId, vehicleId);
		this.passengerAdditionalRequirements = passengerAdditionalRequirements;
		this.currentDriverId = driverId;
		this.currentVehicleId = vehicleId;
		this.currentRequestConfirmed = this.requestLastSent;
		this.requestLastSent = null;
	}

	/**
	 * This callback is called after getting off at the target node
	 */
	@Override
	public void tripFinished(long targetNode) {
		
		//LOGGER.debug("TRIP FINISHED ["+utils.getCurrentTime()+", "+currentDriverId+", "+passengerId+"] at "+targetNode);

		// print out
		String printReq = passengerId + " has finished his trip at " + getCurrentTimeStr();
		TimeWindow timeWin = currentRequestConfirmed.getTimeWindow();
		if (timeWin != null) {
			long delay = timeWin.getArrivalDelay(utils.getCurrentTime());
			int delayMinutes = utils.toMinutes(delay);
			if (delayMinutes > 0) {
				printReq += " (delay " + delayMinutes + " min)";
			} else {
				printReq += " (on time)";
			}
		}
		//LOGGER.debug(printReq);

		// null temporary fields
		currentVehicleId = null;
		currentDriverId = null;
		currentRequestConfirmed = null;
		passengerAdditionalRequirements = null;
	}

	@Override
	public void timeCallback() {
		processVehicleArrivedNoTimeWindows(currentDriverId, currentVehicleId);
	}

	protected boolean hasRequestConfirmed() {
		return currentRequestConfirmed == null ? false : true;
	}

	/**
	 * Process a proposal, that's been just received from a taxi driver.
	 * 
	 * @param proposal
	 *            the new proposal
	 */
	public abstract void processProposal(Proposal proposal);
	
	/**
	 * Process a rejection, that's been just received from a taxi driver.
	 * 
	 * @param rejection
	 *            the new proposal
	 */
	public abstract void processRejection(RequestReject r);
}
