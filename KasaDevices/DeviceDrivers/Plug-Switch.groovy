/*	Kasa Device Driver Series

		Copyright Dave Gutheinz

License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md

===== Version 6.3.2.1) =====
	a.  Drivers (plugs and switches):
		1.	Add LED On/Off commands. Add attribute led to reflect state
		2.	Remove LED On/Off Preference.
	b.	Drivers (all).  change attribute "commsError" to string with values "true" and "false".
		Allows use with Rule Machine.
===================================================================================================*/
def driverVer() { return "6.3.2.1" }
def type() { return "Plug Switch" }
//def type() { return "Dimming Switch" }
//def type() { return "EM Plug" }
def file() {
	def filename = type().replaceAll(" ", "-")
	if (type() == "Dimming Switch") {
		filename = "DimmingSwitch"
	}
	return filename
}
import groovy.json.JsonSlurper

metadata {
	definition (name: "Kasa ${type()}",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/${file()}.groovy"
			   ) {
		capability "Switch"
		if (type() == "Dimming Switch") {
			capability "Switch Level"
			capability "Level Preset"
		}
		capability "Actuator"
		capability "Refresh"
		command "setPollInterval", [[
			name: "Poll Interval in seconds",
			constraints: ["default", "5 seconds", "10 seconds", "15 seconds",
						  "30 seconds", "1 minute", "5 minutes",  "10 minutes",
						  "30 minutes"],
			type: "ENUM"]]
		if (type().contains("EM")) {
			capability "Power Meter"
			capability "Energy Meter"
			attribute "currMonthTotal", "number"
			attribute "currMonthAvg", "number"
			attribute "lastMonthTotal", "number"
			attribute "lastMonthAvg", "number"
		}
		command "ledOn"
		command "ledOff"
		attribute "led", "string"
		attribute "connection", "string"
		attribute "commsError", "string"
	}

	preferences {
		if (type().contains("EM")) {
			input ("emFunction", "bool", 
				   title: "Enable Energy Monitor", 
				   defaultValue: false)
		}
		input ("debug", "bool",
			   title: "30 minutes of debug logging", 
			   defaultValue: false)
		input ("descriptionText", "bool", 
			   title: "Enable information logging", 
			   defaultValue: true)
		input ("bind", "bool",
			   title: "Kasa Cloud Binding",
			   defalutValue: true)
		if (bind && parent.useKasaCloud) {
			input ("useCloud", "bool",
				   title: "Use Kasa Cloud for device control",
				   defaultValue: false)
		}
		input ("rebootDev", "bool",
			   title: "Reboot device <b>[Caution]</b>",
			   defaultValue: false)
	}
}

//	======================================
//	===== Code common to all drivers =====
//	======================================
def installed() {
	def message = "Installing new device."
	if (parent.useKasaCloud) {
		message += "\n\t\t\t<b>Application set to useKasaCloud.</b>  System will attempt to install the"
		message += "\n\t\t\tdevice as a cloud device."
		message += "\n\t\t\tIf the device is unbound from the kasaCloud, there will be errors on install."
		message += "\n\t\t\tTo recover set Bind and useKasaCloud in preferences and Save Preferences."
	} else {
		message += "\n\t\t\t<b>Application set to LAN installation.</b>  System will attempt to install the"
		message += "\n\t\t\tdevice as a LAN device."
		message += "\n\t\t\tIf the device can't use cloud, you will need to go to the Kasa App, select"
		message += "\n\t\t\tuseKasaCloud, and successfully enter credentials to properly install the device."
		message += "\n\t\t\tBefore doing this, you will need to uninstall this device."
	}
	message += "\n\t\t\t<b>This installation will use the CLOUD communications if you have set"
	logInfo(message)
	state.errorCount = 0
	if (parent.useKasaCloud) {
		logInfo("install: Installing as CLOUD device.")
		device.updateSetting("useCloud", [type:"bool", value: true])
		sendEvent(name: "connection", value: "CLOUD")
	} else {
		logInfo("install: Installing as LAN device")
		sendEvent(name: "connection", value: "LAN")
		device.updateSetting("useCloud", [type:"bool", value: false])
	}
	state.pollInterval = "30 minutes"
	updateDataValue("driverVersion", driverVer())
	runIn(5, updated)
}

def updated() {
	if (rebootDev) {
		//	First to run with  10 second wait to continue.
		logWarn("updated: ${rebootDevice()}")
	}

	if (state.socketStatus) { state.remove("socketStatus") }
	if (state.response) { state.remove("response") }
	if (state.respLength) {state.remove("respLength") }
	logInfo("updated: Updating device preferences and settings.")
	unschedule()
	logDebug("updated: ${updateDriverData()}")
	//	update data based on preferences
	if (debug) { runIn(1800, debugOff) }
	logDebug("updated: Debug logging is ${debug}")
	logDebug("updated: Info logging is ${descriptionText}")
	logDebug("updated: ${bindUnbind()}")
	sendEvent(name: "commsError", value: "false")
	
	if(type().contains("Bulb")) {
		logDebug("updated: Default Transition Time = ${transition_Time} seconds.")
		logDebug("updated: High Resolution Color is ${highRes}")
	}
		
	//	Update scheduled methods
	if (type().contains("EM") || type().contains("Bulb")) {
		logDebug("updated: ${setupEmFunction()}")
	}
	logDebug("updated: ${setPolling()}")
	runIn(1, refresh)
	
	runIn(2, getSystemData)
}

//	===== Energy Monitor Methods =====
def getPower() {
	if (type().contains("Multi")) {
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
				""""emeter":{"get_realtime":{}}}""")
	} else if (type().contains("Bulb")) {
		sendCmd("""{"smartlife.iot.common.emeter":{"get_realtime":{}}}""")
	} else {
		sendCmd("""{"emeter":{"get_realtime":{}}}""")
	}
}

def setPower(response) {
	def power = response.power
	if (power == null) { power = response.power_mw / 1000 }
	power = Math.round(10*(power))/10
	def curPwr = device.currentValue("power")
	if (curPwr < 5 && (power > curPwr + 0.3 || power < curPwr - 0.3)) {
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W", type: "digital")
		logDebug("polResp: power = ${power}")
	} else if (power > curPwr + 5 || power < curPwr - 5) {
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W", type: "digital")
		logDebug("polResp: power = ${power}")
	}
}

def getEnergyToday() {
	logDebug("getEnergyToday")
	def year = new Date().format("yyyy").toInteger()
	if (type().contains("Multi")) {
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
				""""emeter":{"get_monthstat":{"year": ${year}}}}""")
	} else if (type().contains("Bulb")) {
		sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year}}}}""")
	} else {
		sendCmd("""{"emeter":{"get_monthstat":{"year": ${year}}}}""")
	}
}

def setEnergyToday(response) {
	logDebug("setEnergyToday: response = ${response}")
	def month = new Date().format("M").toInteger()
	def data = response.month_list.find { it.month == month }
	def energy = data.energy
	if (energy == null) { energy = data.energy_wh/1000 }
	energy -= device.currentValue("currMonthTotal")
	energy = Math.round(100*energy)/100
	def currEnergy = device.currentValue("energy")
	if (currEnergy < energy + 0.05) {
		sendEvent(name: "energy", value: energy, descriptionText: "KiloWatt Hours", unit: "KWH")
		logDebug("setEngrToday: [energy: ${energy}]")
	}
	setThisMonth(response)
}

def setThisMonth(response) {
	logDebug("setThisMonth: response = ${response}")
	def month = new Date().format("M").toInteger()
	def day = new Date().format("d").toInteger()
	def data = response.month_list.find { it.month == month }
	def totEnergy = data.energy
	if (totEnergy == null) { 
		totEnergy = data.energy_wh/1000
	}
	totEnergy = Math.round(100*totEnergy)/100
	def avgEnergy = 0
	if (day != 1) { 
		avgEnergy = totEnergy /(day - 1) 
	}
	avgEnergy = Math.round(100*avgEnergy)/100

	sendEvent(name: "currMonthTotal", value: totEnergy, 
			  descriptionText: "KiloWatt Hours", unit: "kWh")
	sendEvent(name: "currMonthAvg", value: avgEnergy, 
			  descriptionText: "KiloWatt Hours per Day", unit: "kWh/D")
	logDebug("setThisMonth: Energy stats set to ${totEnergy} // ${avgEnergy}")
	if (month != 1) {
		setLastMonth(response)
	} else {
		def year = new Date().format("yyyy").toInteger()
		if (type().contains("Multi")) {
			sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
					""""emeter":{"get_monthstat":{"year": ${year}}}}""")
		} else if (type().contains("Bulb")) {
			sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year}}}}""")
		} else {
			sendCmd("""{"emeter":{"get_monthstat":{"year": ${year}}}}""")
		}
	}
}

def setLastMonth(response) {
	logDebug("setLastMonth: response = ${response}")
	def year = new Date().format("yyyy").toInteger()
	def month = new Date().format("M").toInteger()
	def day = new Date().format("d").toInteger()
	def lastMonth
	if (month == 1) {
		lastMonth = 12
	} else {
		lastMonth = month - 1
	}
	def monthLength
	switch(lastMonth) {
		case 4:
		case 6:
		case 9:
		case 11:
			monthLength = 30
			break
		case 2:
			monthLength = 28
			if (year == 2020 || year == 2024 || year == 2028) { monthLength = 29 }
			break
		default:
			monthLength = 31
	}
	def data = response.month_list.find { it.month == lastMonth }
	def totEnergy
	if (data == null) {
		totEnergy = 0
	} else {
		totEnergy = data.energy
		if (totEnergy == null) { 
			totEnergy = data.energy_wh/1000
		}
		totEnergy = Math.round(100*totEnergy)/100
	}
	def avgEnergy = 0
	if (day !=1) {
		avgEnergy = totEnergy /(day - 1)
	}
	avgEnergy = Math.round(100*avgEnergy)/100
	sendEvent(name: "lastMonthTotal", value: totEnergy, 
			  descriptionText: "KiloWatt Hours", unit: "kWh")
	sendEvent(name: "lastMonthAvg", value: avgEnergy, 
			  descriptionText: "KiloWatt Hoursper Day", unit: "kWh/D")
	logDebug("setLastMonth: Energy stats set to ${totEnergy} // ${avgEnergy}")
}

//	===== Communications =====
def sendCmd(command) {
	if (device.currentValue("connection") == "LAN") {
		sendLanCmd(command)
	} else if (device.currentValue("connection") == "CLOUD"){
		sendKasaCmd(command)
	} else {
		logWarn("sendCmd: attribute connection not set.")
	}
}

def sendLanCmd(command) {
	logDebug("sendLanCmd: command = ${command}")
	state.lastLanCmd = command
	def myHubAction = new hubitat.device.HubAction(
		outputXOR(command),
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "${getDataValue("deviceIP")}:9999",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
		 parseWarning: true,
		 timeout: 2])
	sendHubCommand(myHubAction)
}

def parse(message) {
	def resp = parseLanMessage(message)
	if (resp.type != "LAN_TYPE_UDPCLIENT") {
		def errMsg = "LAN Error = ${resp.type}"
		logDebug("parse: ${errMsg}]")
		handleCommsError([state.lastLanCmd, errMsg])
		return
	}
	def clearResp = inputXOR(resp.payload)
	if (clearResp.length() > 1022) {
		clearResp = clearResp.substring(0,clearResp.indexOf("preferred")-2) + "}}}"
	}
	def cmdResp = new JsonSlurper().parseText(clearResp)
	distResp(cmdResp)
}

def sendKasaCmd(command) {
	logDebug("sendKasaCmd: ${command}")
	def cmdResponse = ""
	def cmdBody = [
		method: "passthrough",
		params: [
			deviceId: getDataValue("deviceId"),
			requestData: "${command}"
		]
	]
	def sendCloudCmdParams = [
		uri: "${parent.kasaCloudUrl}/?token=${parent.kasaToken}",
		requestContentType: 'application/json',
		contentType: 'application/json',
		headers: ['Accept':'application/json; version=1, */*; q=0.01'],
		timeout: 5,
		body : new groovy.json.JsonBuilder(cmdBody).toString()
	]
	try {
		httpPostJson(sendCloudCmdParams) {resp ->
			if (resp.status == 200 && resp.data.error_code == 0) {
				def jsonSlurper = new groovy.json.JsonSlurper()
				distResp(jsonSlurper.parseText(resp.data.result.responseData))
			} else {
				def errMsg = "CLOUD Error = ${resp.data}"
				logDebug("sendKasaCmd: ${errMsg}]")
				handleCommsError([command, errMsg])
			}
		}
	} catch (e) {
				def errMsg = "CLOUD Error = ${e}"
				logDebug("sendKasaCmd: ${errMsg}]")
				handleCommsError([command, errMsg])
	}
}

def handleCommsError(errorData) {
	def count = state.errorCount + 1
	state.errorCount = count
	def errData = errorData[1]
	def command = errorData[0]
	def message = "handleCommsError: Count: ${count}."
	if (count <= 3) {
		message += "\n\t\t\t Retransmitting command, try = ${count}"
		runIn(2, sendCmd, [data: command])
	} else if (count == 4) {
		setCommsError(errData)
		message += "\n\t\t\t Setting Comms Error."
	}
	logDebug(message)
		
}

def setCommsError(errorData) {
	def message = "setCommsError: Four consecutive errors.  Setting commsError to true."
	message += "\n\t\t<b>ErrorData = ${ErrorData}</b>."
	sendEvent(name: "commsError", value: "true")
	state.commsErrorText = "<b>${errorData}</b>"
	message += "\n\t\t${parent.fixConnection(device.currentValue("connection"))}"
	logWarn message
	runIn(2, refresh)
}

def resetCommsError() {
	if (state.errorCount >= 4) {
		sendEvent(name: "commsError", value: "false")
		state.remove("commsErrorText")
	}
	state.errorCount = 0
}

//	===== Preference Methods =====
def setPolling() {
	def message = "Setting Poll Intervals."
	def interval = "30 minutes"
	if (state.pollInterval) {
		interval = state.pollInterval
	}
	message += "\n\t\t\t OnOff Polling set to ${interval}."
	setPollInterval(interval)
	state.remove("powerPollInterval")
	state.remove("powerPollWarning")
	return message
}

def setPollInterval(interval) {
	logDebug("setPollInterval: interval = ${interval}.")
	if (interval == "default" || interval == "off") {
		interval = "30 minutes"
	} else if (useCloud && interval.contains("sec")) {
		interval = "1 minute"
	}
	if (interval.contains("sec")) {
		state.pollWarning = "<b>Polling intervals of less than one minute can take high " +
								 "resources and impact hub performance.</b>"
	} else {
		state.remove("pollWarning")
	}
	state.pollInterval = interval
	schedInterval("poll", interval)
}

def schedInterval(pollType, interval) {
	logDebug("schedInterval: type = ${pollType}, interval = ${interval}.")
	def message = ""
	def pollInterval = interval.substring(0,2).toInteger()
	if (interval.contains("sec")) {
		def start = Math.round((pollInterval-1) * Math.random()).toInteger()
		schedule("${start}/${pollInterval} * * * * ?", pollType)
		message += "${pollType} Interval set to ${interval} seconds."
	} else {
		def start = Math.round(59 * Math.random()).toInteger()
		schedule("${start} */${pollInterval} * * * ?", pollType)
		message += "${pollType} Interval set to ${interval} minutes."
	}
}

def setupEmFunction() {
	if (emFunction) {
		sendEvent(name: "power", value: 0)
		sendEvent(name: "energy", value: 0)
		sendEvent(name: "currMonthTotal", value: 0)
		sendEvent(name: "currMonthAvg", value: 0)
		sendEvent(name: "lastMonthTotal", value: 0)
		sendEvent(name: "lastMonthAvg", value: 0)
		def start = Math.round(30 * Math.random()).toInteger()
		schedule("${start} */30 * * * ?", getEnergyToday)
		runIn(1, getEnergyToday)
		return "Energy Monitor Function initialized."
	} else if (device.currentValue("power") != null) {
		sendEvent(name: "power", value: 0)
		sendEvent(name: "energy", value: 0)
		sendEvent(name: "currMonthTotal", value: 0)
		sendEvent(name: "currMonthAvg", value: 0)
		sendEvent(name: "lastMonthTotal", value: 0)
		sendEvent(name: "lastMonthAvg", value: 0)
		if (type().contains("Multi")) {
			state.remove("powerPollInterval")
		}
		return "Energy Monitor Function not enabled. Data element set to 0."
	} else {
		return "energy Monitor Function not enabled.  Data elements already null."
	}
}

def rebootDevice() {
	logWarn("rebootDevice: User Commanded Reboot Device!")
	device.updateSetting("rebootDev", [type:"bool", value: false])
	if (type().contains("Bulb")) {
		sendCmd("""{"smartlife.iot.common.system":{"reboot":{"delay":1}}}""")
	} else {
		sendCmd("""{"system":{"reboot":{"delay":1}}}""")
	}
	pauseExecution(10000)
	return "REBOOTING DEVICE"
}

def bindUnbind() {
	logDebug("bindUnbind: ${bind}")
	def message = ""
	def meth = "cnCloud"
	if (type().contains("Bulb")) {
		meth = "smartlife.iot.common.cloud"
	}
	if (bind == null) {
		//	Set bind to true.  Attempt to get.
		//	If update fails, device is considered cloud only and true is correct.
		message += "Bind value null.  Set to true and attempt to get actual."
		device.updateSetting("bind", [type:"bool", value: true])
		sendCmd("""{"${meth}":{"get_info":{}}}""")
	} else if (bind) {
		message += "Attempting to Bind the Device to the Kasa Cloud."
		sendCmd("""{"${meth}":{"bind":{"username":"${parent.userName}",""" +
				""""password":"${parent.userPassword}"}},""" +
				""""${meth}":{"get_info":{}}}""")
	} else if (!bind) {
		message = "Attempting to Unbind the Device from the Kasa Cloud."
		sendCmd("""{"${meth}":{"unbind":""},""" +
				""""${meth}":{"get_info":{}}}""")
	}
	pauseExecution(5000)
	return message
}

def setBindUnbind(cmdResp) {
	if (cmdResp.get_info) {
		def bindState = true
		if (cmdResp.get_info.binded == 0) {
			bindState = false
		}
		if (type().contains("Multi")) {
			parent.coordinate(getDataValue("deviceId"), getDataValue("plugNo"),
							  "bind", bindState)
		} else {
			device.updateSetting("bind", [type:"bool", value: bindState])
		}
		logDebug("setBindUnbind: Bind status set to ${bindState}")
	} else {
		logWarn("setBindUnbind: Unhandled response: ${cmdResp}")
	}
	runIn(1, setCommsType)
}

def setCommsType() {
	def commsType = device.currentValue("connection")
	logDebug("setCommsType: Ctype = ${commsType}, useCloud = ${useCloud}, bind = ${bind}")
	def message = ""
	if (useCloud) {
		if (parent.useKasaCloud && bind) {
			message = "Device set to use CLOUD communications."
			commsType = "CLOUD"
		} else {
			//	Not available due to app setting or device binding to cloud.
			logWarn("setCommsType: <b>Can't set to Kasa cloud communications.</b> Check items:" +
				    "\n\t1.\tKasa Integration Appication" +
				    "\n\t\t* set Interface to Kasa Cloud in the app and validate userName and password" +
					"\n\t\t* the token must not be null.")
			message = "ERROR: Can not use Cloud. Device set to use LAN communications."
			commsType = "LAN"
		}
	} else if (!useCloud) {
		if (!getDataValue("deviceIP")) {
			//	No IP set - may not be able to use device locally.
			logWarn("setCommsType: <b>Device IP is not available.</b>  Device IP not set. " +
				    "Go to Kasa Integration app and run Update Installed Devices.")
			message = "ERROR: No deviceIP.  Device set to use CLOUD communications."
			commsType = "CLOUD"
		} else {
			message = "Device set to use LAN communications."
			commsType = "LAN"
		}
	} else {
		logWarn("setCommsType: useCloud not set to valid value.")
		return
	}

	if (type().contains("Multi")) {
		parent.coordinate(getDataValue("deviceId"), getDataValue("plugNo"), 
						  "lanCloud", commsType)
	} else {
		setCommsData(commsType)
	}
}

def setCommsData(commsType) {
	logDebug("setCommsData: ${commsType}.")
	if (commsType == "CLOUD") {
		state.remove("lastLanCmd")
		device.updateSetting("useCloud", [type:"bool", value: true])
		sendEvent(name: "connection", value: "CLOUD")
	} else {
		state.lastLanCmd = """{"system":{"get_sysinfo":{}}}"""
		device.updateSetting("useCloud", [type:"bool", value: false])
		sendEvent(name: "connection", value: "LAN")
	}
	pauseExecution(1000)
}

def ledOn() {
	logDebug("ledOn: Setting LED to on")
	sendCmd("""{"system":{"set_led_off":{"off":0}}}""")
	sendEvent(name: "led", value: "on")
}

def ledOff() {
	logDebug("ledOff: Setting LED to off")
	sendCmd("""{"system":{"set_led_off":{"off":1}}}""")
	sendEvent(name: "led", value: "off")
}

def getSystemData() {
	def message = "<b>System Data Summary: [Information: </b>["
	message += "Name: ${device.getName()} , "
	message += "Label: ${device.getLabel()} , "
	message += "DNI: ${device.getDeviceNetworkId()}],"
	
	message += "[<b>States: </b>["
	message += "pollInterval: ${state.pollInterval} , "
	message += "powerPollInterval: ${state.powerPollInterval} , "
	message += "errorCount: ${state.errorCount}],"
	
	message += "[<b>Preferences</b>: ["
	if (type().contains("Bulb")) {
		message += "Transition Time: ${transition_Time} , "
	}
	message += "Energy Monitor: ${emFunction} , "
	message += "Cloud Binding: ${bind} , "
	message += "Use Kasa Cloud: ${useCloud}] , "
	
	message += "[<b> Attributes: </b>["
	message += "Connection: ${device.currentValue("connection")} , "
	message += "Comms Error: ${device.currentValue("commsError")} , "
	message += "Switch: ${device.currentValue("switch")} , "
	if (emFunction) {
		message += "Power: ${device.currentValue("power")} , "
		message += "Energy: ${device.currentValue("energy")} , "
		message += "Current Month Daily: ${device.currentValue("currMonthAvg")} , "
		message += "Current Month Total: ${device.currentValue("currMonthTotal")} , "
		message += "Last Month Daily: ${device.currentValue("lastMonthAvg")} , "
		message += "Last Month Total: ${device.currentValue("lastMonthTotal")}]"
	}
	message +="[<b>Device Data:</b> ${device.getData()}] , "
	message +="[<b>Device Attributes:</b> ${device.getCurrentStates()}]]]"
	logInfo(message)
}

//	===== Utility Methods =====
def updateDriverData() {
	def drvVer = getDataValue("driverVersion")
	def doubleDriver = driverVer().substring(0,3).toDouble()
	if (drvVer == driverVer()) {
		return "Driver Data already updated."
	}
	
//	Poll Interval, communications, and bind capture
	def message = "<b>Updating data from driver version ${drvVer}."
	def comType = "LAN"
	def usingCloud = false
	def binding = true
	def interval = "30 minutes"
	def oldDriver = getDataValue("driverVersion")
	if (oldDriver.contains("5.3")) {
		if (state.pollInterval && state.pollInterval != "off") {
			interval = "${state.pollInterval} seconds"
		}
	} else if (oldDriver.contains("6.0")) {
		if (refreshInterval) {
			def inter = refreshInterval.toInteger()
			if (inter < 60) {
				interval = "${inter} seconds"
			}
		}
		if (useCloud) { commType = "CLOUD" }
		if (bind == "0") { binding = false }
	} else if (oldDriver.contains("6.1")) {
		if (state.pollInterval && state.pollInterval != "off") {
			interval = "${state.pollInterval} seconds"
		}
		if (useCloud) { commType = "Cloud" }
		if (bind == "0") { binding = false }
	} else if (oldDriver.contains("6.2")) {
		interval = state.pollInterval
		if (useCloud) { commType = "Cloud" }
		binding = bind
	}
	message += "\n\t\t\t Connection = ${comType}."
	message += "\n\t\t\t bind = ${binding}."
	message += "\n\t\t\t pollInterval = ${interval}."
	setCommsData(comType)
	pauseExecution(200)
	device.updateSetting("bind", [type:"bool", value: binding])
	pauseExecution(200)
	state.pollInterval = interval

//	Settings for all updates
	if (device_IP) {
		device.removeSetting("device_IP")
		pauseExecution(200)
	}
	if (pollTest) {
 		device.removeSetting("pollTest")
		pauseExecution(200)
	}
	if (refresh_Rate) {
		device.removeSetting("refresh_Rate")
		pauseExecution(200)
	}
	if (refreshInterval) {
		device.removeSetting("refreshInterval")
		pauseExecution(200)
	}
	
//	States for all version updates	
	if (state.response) {
		state.remove("response")
		pauseExecution(200)
	}
	if (state.respLength) {
		state.remove("respLength")
		pauseExecution(200)
	}
	if (state.lastConnect) {
		state.remove("lastConnect")
		pauseExecution(200)
	}
	if (state.pollFreq) {
		state.remove("pollFreq")
		pauseExecution(200)
	}
	if (state.WARNING) {
		state.remove("WARNING")
		pauseExecution(200)
	}
	if (state.currentBind) {
		state.remove("currentBind")
		pauseExecution(200)
	}
	if (state.currentCloud) {
		state.remove("currentCloud")
		pauseExecution(200)
	}
	if (type() == "EM Plug Switch" || type().contains("Bulb")) {
		if (state.powerPollInterval) {
			state.remove("powerPollInterval")
			pauseExecution(200)
		}
	}
	if (state.lanErrorsToday) {
		state.remove("lanErrorsToday")
		pauseExecution(200)
	}
	if (state.lanErrorsPrev) {
		state.remove("lanErrorsPrev")
		pauseExecution(200)
	}
	if (state.lanErrosPrev) {
		state.remove("lanErrosPrev")
		pauseExecution(200)
	}
	if (state.communicationsError) {
		state.remove("communicationsError")
		pauseExecution(200)
	}
	if (state.socketTimeout) {
		state.remove("socketTimeout")
		pauseExecution(200)
	}
	if (state.lastColorTemp) {
		state.remove("lastColorTemp")
		pauseExecution(200)
	}
	
	
//	Data values for all updates
	if (getDataValue("appServerUrl")) {
		removeDataValue("appServerUrl")
		pauseExecution(200)
	}
	if (getDataValue("deviceFWVersion")) {
		removeDataValue("deviceFWVersion")
		pauseExecution(200)
	}
	if (getDataValue("lastErrorratio")) {
		removeDataValue("lastErrorRatio")
		pauseExecution(200)
	}
	if (getDataValue("token")) {
		removeDataValue("token")
		pauseExecution(200)
	}
	if (type() == "EM Multi Plug") {
		if (getDataValue("emSysInfo")) {
			removeDataValue("emSysInfo")
			pauseExecution(200)
		}
		if (getDataValue("getPwr")) {
			removeDataValue("getPwr")
			pauseExecution(200)
		}
	}
	if (getDataValue("applicationVersion")) {
		removeDataValue("applicationVersion")
		pauseExecution(200)
	}
	if (!type().contains("Multi")) {
		if (getDataValue("plugNo")) {
			removeDataValue("plugNo")
			pauseExecution(200)
		}
		if (getDataValue("plugId")) {
			removeDataValue("plugId")
			pauseExecution(200)
		}
	}

	updateDataValue("driverVersion", driverVer())
	message += "\n\t\t\tNew Version: ${driverVer()}.</b>"
	return message
}

private outputXOR(command) {
//	UDP Version
	def str = ""
	def encrCmd = ""
 	def key = 0xAB
	for (int i = 0; i < command.length(); i++) {
		str = (command.charAt(i) as byte) ^ key
		key = str
		encrCmd += Integer.toHexString(str)
	}
   	return encrCmd
}

private inputXOR(encrResponse) {
	String[] strBytes = encrResponse.split("(?<=\\G.{2})")
	def cmdResponse = ""
	def key = 0xAB
	def nextKey
	byte[] XORtemp
	for(int i = 0; i < strBytes.length; i++) {
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative
		XORtemp = nextKey ^ key
		key = nextKey
		cmdResponse += new String(XORtemp)
	}
	return cmdResponse
}

def logTrace(msg){
	log.trace "[${type()} / ${driverVer()} / ${device.label}]| ${msg}"
}

def logInfo(msg) {
	if (descriptionText == true) { 
		log.info "[${type()} / ${driverVer()} / ${device.label}]| ${msg}"
	}
}

def logDebug(msg){
	if(debug == true) {
		log.debug "[${type()} / ${driverVer()} / ${device.label}]| ${msg}"
	}
}

def debugOff() {
	device.updateSetting("debug", [type:"bool", value: false])
	logInfo("debugLogOff: Debug logging is off.")
}

def logWarn(msg){
	log.warn "[${type()} / ${driverVer()} / ${device.label}]| ${msg}"
}

//	=================================================
//	===== Plug/Switch Command and Parse Methods =====
//	=================================================
def on() {
	logDebug("on")
	if (type() != "Dimming Switch") {
		sendCmd("""{"system":{"set_relay_state":{"state":1},""" +
				""""get_sysinfo":{}}}""")
		if (emFunction) {
			runIn(10, getPower)
		}
	} else{
		sendCmd("""{"system":{"set_relay_state":{"state":1}}}""")
	}
}

def off() {
	logDebug("off")
	if (type() != "Dimming Switch") {
		sendCmd("""{"system":{"set_relay_state":{"state":0},""" +
				""""get_sysinfo":{}}}""")
	} else{
		sendCmd("""{"system":{"set_relay_state":{"state":0}}}""")
		if (emFunction) {
			runIn(2, getPower)
		}
	}
}

def setLevel(percentage, transition = null) {
	logDebug("setLevel: level = ${percentage}")
	percentage = percentage.toInteger()
	if (percentage < 0) { percentage = 0 }
	if (percentage > 100) { percentage = 100 }
	percentage = percentage.toInteger()
	sendCmd("""{"smartlife.iot.dimmer":{"set_brightness":{"brightness":${percentage}}},""" +
			""""system":{"set_relay_state":{"state":1},"get_sysinfo":{}}}""")
}

def presetLevel(percentage) {
	logDebug("presetLevel: level = ${percentage}")
	percentage = percentage.toInteger()
	if (percentage < 0) { percentage = 0 }
	if (percentage > 100) { percentage = 100 }
	percentage = percentage.toInteger()
	sendCmd("""{"smartlife.iot.dimmer":{"set_brightness":{"brightness":${percentage}}},""" +
			""""system" :{"get_sysinfo" :{}}}""")
}

def refresh() {
	logDebug("refresh")
	poll()
}

def poll() {
	if (!emFunction) {
		sendCmd("""{"system":{"get_sysinfo":{}}}""")
	} else {
		sendCmd("""{"system":{"get_sysinfo":{}},""" +
				""""emeter":{"get_realtime":{}}}""")
	}
}

def setSysInfo(response) {
	logDebug("setSysInfo: ${response}")
	def status = response.system.get_sysinfo
	def onOff = "on"
	if (status.relay_state == 0) { onOff = "off" }
	if (onOff != device.currentValue("switch")) {
		sendEvent(name: "switch", value: onOff, type: "digital")
		logInfo("setSysInfo: switch: ${onOff}")
	}
	if (type() == "Dimming Switch") {
		if (status.brightness != device.currentValue("level")) {
			sendEvent(name: "level", value: status.brightness, type: "digital")
			logInfo("setSysInfo: level: ${status.brightness}")
		}
	}
	if (response.emeter) { setPower(response.emeter.get_realtime) }
}

def distResp(response) {
	if (response.system) {
		if (response.system.get_sysinfo) {
			setSysInfo(response)
		} else if (response.system.set_relay_state) {
			runIn(1, refresh)
		} else if (response.system.reboot) {
			logWarn("distResp: Rebooting device.")
		} else if (response.system.set_led_off.err_code != 0) {
			sendEvent(name: "led", value: "error")
			logWarn("distResp: Setting LED Failed")
		}
	} else if (emFunction && response.emeter) {
		def month = new Date().format("M").toInteger()
		if (response.emeter.get_realtime) {
			setPower(response.emeter.get_realtime)
		} else if (response.emeter.get_monthstat.month_list.find { it.month == month }) {
			setEnergyToday(response.emeter.get_monthstat)
		} else if (response.emeter.get_monthstat.month_list.find { it.month == month - 1 }) {
			setLastMonth(response.emeter.get_monthstat)
		} else {
			logWarn("distResp: Unhandled response = ${response}")
		}
	} else if (response.cnCloud) {
		setBindUnbind(response.cnCloud)
	} else {
		logWarn("distResp: Unhandled response = ${response}")
	}
	resetCommsError()
}

//	End of File
