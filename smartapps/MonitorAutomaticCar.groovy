/**
 *  MonitorAutomaticCar
 *
 *  Copyright 2015 Yves Racine
 *  linkedIn profile: ca.linkedin.com/pub/yves-racine-m-sc-a/0/406/4b/
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
import java.text.SimpleDateFormat
import groovy.json.JsonSlurper

definition(
	name: "MonitorAutomaticCar",
	namespace: "yracine",
	author: "Yves Racine",
	description: "Monitor your Automatic connected Car at regular intervals, based on 2 different cycles throughout the year " +
		"Special monitoring intervals can be specified when raining or snowing if weather station is provided",
	category: "My Apps",
	iconUrl: "https://www.automatic.com/_assets/images/favicons/favicon-32x32-3df4de42.png",
	iconX2Url: "https://www.automatic.com/_assets/images/favicons/favicon-96x96-06fd8c85.png",
	iconX3Url: "https://www.automatic.com/_assets/images/favicons/favicon-96x96-06fd8c85.png"
)

preferences {

	page(name: "monitoringSettings", title: "monitoringSettings")
	page(name: "otherSettings", title: "OtherSettings")

}

def monitoringSettings() {
	dynamicPage(name: "monitoringSettings", install: false, uninstall: true, nextPage: "otherSettings") {
		section("About") {
			paragraph "Monitor your Connected Vehicle at regular intervals, based on 2 different cycles throughout the year" 
			paragraph "Version 1.6\n\n" +
				"If you like this app, please support the developer via PayPal:\n\nyracine@yahoo.com\n\n" +
				"Copyright©2015 Yves Racine"
			href url: "http://github.com/yracine", style: "embedded", required: false, title: "More information...",
			description: "http://github.com/yracine"
		}
		section("Monitor this Automatic Connected Vehicle") {
			input "vehicle", "capability.presenceSensor", title: "Which vehicle?"
		}
		section("Select Months for tight monitoring (ex. school months, Wet/Snow season)") {
			input "givenWetSeasonMonths", "enum",
				title: "Which month(s)?",
				multiple: true,
				required: true,
				metadata: [
					values: ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
				]
		}
		section("During the tight monitoring months, Monitor the Vehicle at this cycle interval in hours ") {
			input "givenWetSeasonIntInHr", "number", title: "Cycle Time in Hours [no values=no monitoring]?", required: false
		}
		section("For the rest of the year, Monitor the Vehicle at this cycle interval in hours") {
			input "givenDrySeasonIntInHr", "number", title: "Cycle Time in Hours [no values=no monitoring]?", required: false
		}
		section("Alert me for these trip events") {
			input "givenEvents", "enum",
				title: "Which Events(s)?",
				multiple: true,
				required: true,
				metadata: [
					values: ["Speed Exceeded Threshold", "Hard Acceleration", "Hard Brake"]
				]
		}
		section("And/or alert me below this Score Events ") {
			input "givenScoreEvents", "decimal",
				title: "Which Score [max value=50]?",
				required: false
		}
		section("And/or alert me below this Score Speeding") {
			input "givenScoreSpeeding", "decimal",
				title: "Which Score [max value=50]?",
				required: false
		}
        
		section("if raining or snowing at this weather station [optional]") {
			input "weatherStation", "capability.waterSensor", title: "Which weather station?", required: false
		}
		section("Change the cycle time from hours to minutes (no values=no special monitoring, range=[10..59])") {
			input "givenRainIntInMin", "number", title: "Cycle Time in Minutes?", required: false
		}
	}
}


def otherSettings() {
	dynamicPage(name: "otherSettings", title: "Other Settings", install: true, uninstall: false) {
		section("Alert me by turning on these lights [optional]") {
			input "switches", "capability.switch", required: false, multiple: true, title: "Which lights?"
			input "lightMode", "enum", metadata: [values: ["Flash Lights", "Turn On Lights"]], required: false, defaultValue: "Turn On Lights", title: "Action?"
		}
		section("Notifications") {
			input "sendPushMessage", "enum", title: "Send a push notification?", metadata: [values: ["Yes", "No"]], required:
				false
			input "phoneNumber", "phone", title: "Send a text message?", required: false
		}
		section("Send vehicle Event Notifications") {
			input "detailedNotif", "Boolean", title: "Send any upcoming Vehicle Event Notifications?", metadata: [values: ["true", "false"]], required:
				false
		}
		section([mobileOnly: true]) {
			label title: "Assign a name for this SmartApp", required: false
		}
	}
}




def installed() {
	initialize()
}

def updated() {
	unschedule()
	unsubscribe()
	initialize()
}

def initialize() {
	state.msg=null
	subscribe(vehicle, "eventType", eventTypeHandler)
	if (weatherStation) {
		subscribe(weatherStation, "water.wet", checkRainyWeather)
		subscribe(weatherStation, "weather", checkRainyWeather)
	}        
	scheduleJobs()
	subscribe(app, appTouch)
}


def appTouch(evt) {
	checkRunningIntHr()
}



def eventTypeHandler(evt) {
	def HARD_ACCEL="Hard Acceleration"
	def HARD_BRAKE="Hard Brake"
	def SPEEDING="Speed Exceeded Threshold"
	def TRIP_COMPLETED="Trip Completed"
	def msg
	def tripFields,eventCreatedAt
    
	log.debug "eventTypeHandler>evt.value= ${evt.value}"
	if (detailedNotif != 'true') {	
		// do not send msg if detailedNotif is false    
		return
	}
	msg = "MonitorAutomaticCar>${vehicle} vehicle has triggerred ${evt.value} event..."
	log.debug msg
	// get Trip Data		    
	def tripId= vehicle.currentEventTripId
	log.debug "eventTypeHandler>eventTripId = $tripId"
	vehicle.getTrips("",tripId,null,null,null,'true')
	String tripData = vehicle.currentTripsData
	if (!tripData) {
		return        
	}     
	try {
		tripFields = new JsonSlurper().parseText(tripData)   
		log.debug "eventTypeHandler>tripFields = $tripFields"
	} catch (e) {
		log.error("eventTypeHandler>tripData not formatted correctly or empty (exception $e), exiting")
		return
	} 
	String startAddress=tripFields?.start_address.name
	String endAddress=tripFields?.end_address.name
	def vehicleEvents=(tripFields?.vehicle_events instanceof List)?    
		tripFields?.vehicle_events[0]:
		tripFields?.vehicle_events
	vehicleEvents.each {

		if ((it.type=='speeding') && (evt.value==SPEEDING)) {
			def speed =it.velocity_kph            
			if (speed) {
				float speedValue=getSpeed(speed)
				eventCreatedAt=it.started_at
				msg = "MonitorAutomaticCar>${vehicle} vehicle was speeding (${speedValue} ${getSpeedScale()}) at ${eventCreatedAt} on trip ${tripId} from ${startAddress} to ${endAddress}"
				send msg
			}                
		}            
		if ((it.type=='hard_brake') && (evt.value == HARD_BRAKE)) {
			eventCreatedAt=it.created_at
			msg = "MonitorAutomaticCar>${vehicle} vehicle triggerred the ${evt.value} event at ${eventCreatedAt} on trip ${tripId} from ${startAddress} to ${endAddress} "
			send msg
		}     
		if ((it.type == 'hard_accel') && (evt.value == HARD_ACCEL)) {
			eventCreatedAt=it.created_at   
			msg = "MonitorAutomaticCar>${vehicle} vehicle triggerred the ${evt.value} event at ${eventCreatedAt} on trip ${tripId} from ${startAddress} to ${endAddress}"
			send msg
		}
	} /* end each vehicle event */        
	if (evt.value == TRIP_COMPLETED) {
		eventCreatedAt= tripFields.ended_at.isCollectionOrArray()?tripFields.ended_at[0]:tripFields.ended_at
		msg = "MonitorAutomaticCar>${vehicle} vehicle triggerred the ${evt.value} event at ${eventCreatedAt}"
		send msg
	}        
}

private def get_all_detailed_trips_info() {
	String dateInLocalTime = new Date().format("yyyy-MM-dd", location.timeZone) 
	String timezone = new Date().format("zzz", location.timeZone)
	String dateAtMidnight = dateInLocalTime + " 00:00 " + timezone    
	if (settings.trace) {
		log.debug("get_all_detailed_trips_info>date at Midnight in UTC= ${dateAtMidnight}")
	}
	Date endDate = formatDate(dateAtMidnight) 

//	Generate stats for the past week

	Date startDate = (endDate -2)

	if (settings.trace) {
		log.debug("get_all_detailed_trips_info>past week (last 7 days): startDate in UTC = ${String.format('%tF %<tT',startDate)}," +
			"endDate in UTC= ${String.format('%tF %<tT', endDate)}")
	}
	vehicle.getTrips("","", startDate,endDate, null, 'true')
	def currentTripList = vehicle.currentTripsList
	def tripFields =null   
	if (!currentTripList) {
		log.debug "get_all_detailed_trips_info> empty tripList exiting"
		return    	
	} 
	def tripList=currentTripList.toString().split(",")    
	log.debug "get_all_detailed_trips_info> tripList= $tripList"

	tripList.each {
		String tripId = it
		log.debug "get_all_detailed_trips_info> tripId= $tripId"
		vehicle.getTrips("",tripId,null,null,null,'true')
		String tripData = vehicle.currentTripsData
		if (!tripData) {
			return        
		}     
		try {
			tripFields = new JsonSlurper().parseText(tripData)   
			log.debug "get_all_detailed_trips_info>tripFields.vehicle_events = $tripFields.vehicle_events"
		} catch (e) {
			log.error("get_all_detailed_trips_info>tripData not formatted correctly or empty (exception $e), exiting")
			return
		} 
		String startAddress=tripFields.start_address.name
		String endAddress=tripFields.end_address.name        
		String eventCreatedAt
		def vehicleEvents=(tripFields?.vehicle_events instanceof List)?    
			tripFields?.vehicle_events[0]:
			tripFields?.vehicle_events
        
		log.debug "get_all_detailed_trips_info>parsed vehicleEvents = $vehicleEvents"
		vehicleEvents.each {
			def type = it.type        
			log.debug ("event type= ${type}, startAddress: ${startAddress}, endAddress=${endAddress}")
			if (type == 'speeding') {
				def speed =it.velocity_kph            
				float speedValue=getSpeed(speed)
				eventCreatedAt=it.started_at   
				log.debug ("event startedAt: ${it.started_at}, timezone=${tripFields.end_timezone}")
				def msg = "MonitorAutomaticCar>${vehicle} vehicle was speeding (${speedValue} ${getSpeedScale()}) at ${eventCreatedAt} on trip ${tripId} from ${startAddress} to ${endAddress}"
				send msg            
			}            
	        
			if (type =='hard_brake') {
				log.debug ("event createdAt: ${it.created_at}, timezone=${tripFields.end_timezone}")
				eventCreatedAt=it.created_at   
				def msg = "MonitorAutomaticCar>${vehicle} vehicle triggerred the ${type} event at ${eventCreatedAt} on trip ${tripId} from ${startAddress} to ${endAddress} "
				send msg            
			}                
			if (type=='hard_accel') {
				eventCreatedAt=it.created_at   
				log.debug ("event createdAt: ${it.created_at}, timezone=${tripFields.end_timezone}")
				def msg = "MonitorAutomaticCar>${vehicle} vehicle triggerred the ${type} event at ${eventCreatedAt} on trip ${tripId} from ${startAddress} to ${endAddress}"
				send msg            
			}
		} /* end each vehicle Event */       
	} /* end each Trip List */
}

private def getSpeed(value) {
	if (!value) {
		return 0    
	}    
	if(getTemperatureScale() == "C"){
		return value
	} else {
		return milesToKm(value)
	}
}

private def getSpeedScale() {
	def scale= getTemperatureScale()
	if (scale == 'C') {
		return "kmh"
	}
	return "mph"
}

private def milesToKm(distance) {
	if (!distance) {
		return 0    
	}    
	return (distance * 1.609344) 
}

private def kmToMiles(distance) {
	if (!distance) {
		return 0    
	}    
	return (distance * 0.62137) 
}

private String formatDateInLocalTime(dateInString, timezone='') {
	def myTimezone
	if ((dateInString==null) || (dateInString.trim()=="")) {
		return ""    
	}    
	SimpleDateFormat ISODateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
	Date ISODate = ISODateFormat.parse(dateInString)
	if (timezone) {
		myTimezone= TimeZone.getTimeZone(timezone)    
	} else {
		myTimezone=  location.timeZone  
	}    
	String dateInLocalTime = ISODate.format("yyyy-MM-dd HH:mm",myTimezone )
	return dateInLocalTime
}

private def formatDate(dateString) {
	SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm zzz")
	Date aDate = sdf.parse(dateString)
	return aDate
}

private boolean check_event(eventType, intervalInMinutes) {
	def msg
	def cycleTimeMsec = intervalInMinutes  * 60 * 1000
	def eventStates = vehicle.statesSince("eventType", new Date((now() - cycleTimeMsec) as Long))
	boolean foundEvent=false
	float nbHours= (intervalInMinutes.toFloat()/60).round(1)
    
	if ((givenEvents.contains(eventType))) {
		if (!eventStates.find {(it.value == eventType)}) {
			if (nbHours>1) {
				msg ="MonitorAutomaticCar>${vehicle} vehicle has not triggerred any ${eventType} events in the last ${nbHours} hours"
			} else {
				msg ="MonitorAutomaticCar>${vehicle} vehicle has not triggerred any ${eventType} events in the last ${intervalInMinutes} minutes"
			}            
			log.debug msg
			if (detailedNotif == 'true') {
				send msg
			}
		} else {
    
			def countEventType = eventStates.count {it.value && it.value == eventType} 
			if (nbHours>1) {
				msg = "MonitorAutomaticCar>${vehicle} vehicle has triggerred ${countEventType} ${eventType} event(s) in the last ${nbHours} hours"
			} else {                
				msg = "MonitorAutomaticCar>${vehicle} vehicle has triggerred ${countEventType} ${eventType} event(s) in the last ${intervalInMinutes} minutes"
			}                
			send msg
			foundEvent=true        
			if (switches) {

				if (lightMode?.equals("Turn On Lights")) {
					switches.on()
				} else {
					flashLights()
				}	
			}                
		}            
	}
	return foundEvent
}

private boolean check_score(scoreType, minScoreThreshold) {
	def msg
	boolean scoreTooLow=false
	float currentScore=50
    		
	def score=(scoreType=='scoreSpeeding')?vehicle.currentTripsAvgScoreSpeeding:vehicle.currentTripsAvgScoreEvents
	if (score) {
		currentScore=score.toFloat()
	}        
	int nbTrips=vehicle.currentTotalNbTrips?.toInteger()    
	log.debug("check_score>nbTrips=$nbTrips, score=$score")
	if ((!nbTrips) || (nbTrips==0)) {
		log.debug("check_score>${scoreType} has not been calculated yet, no trips available, exiting")
		return	
	}    
    
	if (currentScore < minScoreThreshold) {
		msg= "MonitorAutomaticCar>${vehicle} vehicle has a low ${scoreType} (${currentScore}), your minimum threshold is ${minScoreThreshold}"
		send msg
		scoreTooLow=true
	} else {
		msg = "MonitorAutomaticCar>${vehicle} vehicle's ${scoreType} is currently higher than (or equal to) your minimum threshold (${minScoreThreshold})"
		log.debug msg
		if (detailedNotif == 'true') {
			send msg
		}
	}    
	return scoreTooLow
}

def checkRunningIntHr() {
	def HARD_ACCEL="Hard Acceleration"
	def HARD_BRAKE="Hard Brake"
	def SPEEDING="Speed Exceeded Threshold"
    
	log.trace "MonitorAutomaticCar>About to start checkRunningIntHr()"

	def intervalInHour = (isWetSeason()) ? givenWetSeasonIntInHr : givenDrySeasonIntInHr
	log.trace "MonitorAutomaticCar>checkRunningIntHr() running at ${intervalInHour}-hour interval"

	// Get the vehicle's latest values 
	vehicle.poll()
    
	check_event(HARD_ACCEL, (intervalInHour*60))
	check_event(HARD_BRAKE, (intervalInHour*60))
	check_event(SPEEDING, (intervalInHour*60))
	if (givenScoreEvents) {
		check_score("scoreEvents",givenScoreEvents)		    	
	}    
	if (givenScoreSpeeding) {
		check_score("scoreSpeeding",givenScoreSpeeding)		    	
	}    
	get_all_detailed_trips_info()    
	scheduleJobs()

}



def checkRunningIntMin() {
	def HARD_ACCEL="Hard Acceleration"
	def HARD_BRAKE="Hard Brake"
	def SPEEDING="Speed Exceeded Threshold"

	log.trace "MonitorAutomaticCar>About to start checkRunningIntMin()"

	def intervalInMin = givenRainIntInMin

	log.trace "MonitorAutomaticCar>checkRunningIntMin() running at ${intervalInMin}-minute interval"
	
	// Get the vehicle's latest values 
	vehicle.poll()

	check_event(HARD_ACCEL, intervalInMin)
	check_event(HARD_BRAKE, intervalInMin)
	check_event(SPEEDING, intervalInMin)
	if (givenScoreEvents) {
		check_score("scoreEvents",givenScoreEvents)		    	
	}    
	if (givenScoreSpeeding) {
		check_score("scoreSpeeding",givenScoreSpeeding)		    	
	}    
    
	def rainCheck = checkRainyWeather()
	def weather = weatherStation?.currentWeather
	if ((rainCheck != 'wet') && (rainCheck != 'snow' ) && (!weather?.toUpperCase().contains("RAIN") && (!weather?.toUpperCase().contains("SNOW")))) {  
		// unschedule special monitoring if not raining or snowing
		scheduleJobs()
	}
}



private flashLights() {
	def doFlash = true
	def onFor = onFor ?: 1000
	def offFor = offFor ?: 1000
	def numFlashes = numFlashes ?: 3

	log.debug "LAST ACTIVATED IS: ${state.lastActivated}"
	if (state.lastActivated) {
		def elapsed = now() - state.lastActivated
		def sequenceTime = (numFlashes + 1) * (onFor + offFor)
		doFlash = elapsed > sequenceTime
		log.debug "DO FLASH: $doFlash, ELAPSED: $elapsed, LAST ACTIVATED: ${state.lastActivated}"
	}

	if (doFlash) {
		log.debug "FLASHING $numFlashes times"
		state.lastActivated = now()
		log.debug "LAST ACTIVATED SET TO: ${state.lastActivated}"
		def initialActionOn = switches.collect {
			it.currentSwitch != "on"
		}
		def delay = 1 L
		numFlashes.times {
			log.trace "Switch on after  $delay msec"
			switches.eachWithIndex {
				s, i ->
					if (initialActionOn[i]) {
						s.on(delay: delay)
					} else {
						s.off(delay: delay)
					}
			}
			delay += onFor
			log.trace "Switch off after $delay msec"
			switches.eachWithIndex {
				s, i ->
					if (initialActionOn[i]) {
						s.off(delay: delay)
					} else {
						s.on(delay: delay)
					}
			}
			delay += offFor
		}
	}
}




private def isWetSeason() {
	def c = new GregorianCalendar()
	String currentMonth = (c.get(Calendar.MONTH) + 1).toString()
	SimpleDateFormat monthParse = new SimpleDateFormat("MM")
	SimpleDateFormat monthDisplay = new SimpleDateFormat("MMM")
	def currentMonthName = monthDisplay.format(monthParse.parse(currentMonth))
	log.debug "currentMonthName=$currentMonthName"

	if (givenWetSeasonMonths.contains(currentMonthName)) {
		log.trace "Wet/Snow Season detected, month=${currentMonthName}, Wet/Snow Season Months=${givenWetSeasonMonths}"
		return true
	}
	log.trace "Dry Season detected, month=${currentMonthName}"
	return false
}

private def isFirstDayOfMonth() {
	def c = new GregorianCalendar()
	def currentDay = c.get(Calendar.DAY_OF_MONTH)

	if (currentDay == 1) {
		return true

	}
	return false

}


def checkRainyWeather(evt) {
	def latestValue = weatherStation?.latestValue("water")
	def msg
    
	log.debug "checkRainyWeather> latestValue= $latestValue"
	def weather = weatherStation?.currentWeather
        
	if ((rainCheck == 'wet' || rainCheck == 'snow' ) || ((weather?.toUpperCase().contains("RAIN")) || (weather?.toUpperCase().contains("SNOW")))) {
		if (detailedNotif == 'true') {
			msg = "MonitorAutomaticCar>it's raining or snowing at ${weatherStation}, will start special monitoring"
			send msg
		}
		scheduleJobs()	            
	}            
	return latestValue    
    
}


def scheduleJobs() {
	def msg, delay

	state.msg=null

	if (weatherStation) {
		weatherStation.poll()
		def rainCheck =  weatherStation?.latestValue("water")
		def weather = weatherStation?.currentWeather
        
		log.trace "About to reschedule, rain checked =${rainCheck}, current weather = ${weather}"
		if ((rainCheck == 'wet' || rainCheck == 'snow' ) || ((weather?.toUpperCase().contains("RAIN")) || (weather?.toUpperCase().contains("SNOW"))) && (givenRainIntInMin)) {  // schedule special monitoring during rain
			if ((givenRainIntInMin < 10) || (givenRainIntInMin > 59)) {
				state.msg = "MonitorAutomaticCar>cycle interval when raining/snowing in minutes (${givenRainIntInMin}) is not within range,please restart"
				log.error state.msg
				runIn((1*60), "sendWithDelay" )
				return
 			} else {
				if (detailedNotif == 'true') {
					state.msg = "MonitorAutomaticCar> raining or snowing at ${weatherStation}, start special monitoring every ${givenRainIntInMin} min."  
					runIn((1*60), "sendWithDelay" )
				}
				schedule("0 0/${givenRainIntInMin} * * * ?", checkRunningIntMin)
				return
			}
		} else if ((rainCheck != 'wet') && (rainCheck != 'snow')) {
    
			unschedule()
			if (detailedNotif == 'true') {
				msg = "MonitorAutomaticCar>not raining or snowing at the moment,continue scheduling at regular interval"  
				log.trace msg 			
			}
		}
	}


	if (isWetSeason()) {

		if (detailedNotif == 'true') {
			state.msg = "MonitorAutomaticCar>during wet/snow season, monitor ${vehicle} every ${givenWetSeasonIntInHr} hour(s)"
			runIn((1*60), "sendWithDelay" )
		}
		delay = givenWetSeasonIntInHr * 60 * 60

	} else {

		if (detailedNotif == 'true') {
			state.msg = "MonitorAutomaticCar>during dry season, monitor ${vehicle} every ${givenDrySeasonIntInHr} hour(s)"
			runIn((1*60), "sendWithDelay" )
		}
		delay = givenDrySeasonIntInHr * 60 * 60
	}
	runIn(delay, "checkRunningIntHr")

}

private def sendWithDelay() {
	
	if (state.msg) {
		send(state.msg)
	}
}




private def send(msg) {
	if (sendPushMessage != "No") {
		log.debug("sending push message")
		sendPush(msg)

	}

	if (phoneNumber) {
		log.debug("sending text message")
		sendSms(phoneNumber, msg)
	}

	log.debug msg
}
