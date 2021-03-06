/*
===== Blebox Hubitat Integration Driver

	Copyright 2019, Dave Gutheinz

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file except in compliance with the
License. You may obtain a copy of the License at: http://www.apache.org/licenses/LICENSE-2.0.
Unless required by applicable law or agreed to in writing,software distributed under the License is distributed on an 
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific 
language governing permissions and limitations under the License.

DISCLAIMER: The author of this integration is not associated with blebox.  This code uses the blebox
open API documentation for development and is intended for integration into the Hubitat Environment.

===== Hiatory =====
8.14.19	Various edits.
08.15.19	1.1.01. Modified implementaton based on design notes.
09.20.19	1.2.01.	a.  Added link to Application that will check/update IPs if the communications fail.
					b.	Added configure method that sets as gate type (mode).
					c.	Combined gateBox and doorBox drivers into one.
10.01.19	1.3.01. Updated error handling.
02.16.20	1.3.02	Added username and password as an optional Authorization Code.
04.20.20	1.4.0	Hubitat Package Manager Update
*/
//	===== Definitions, Installation and Updates =====
def driverVer() { return "1.4.0" }

metadata {
	definition (name: "bleBox gateBox",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/bleBoxDevices/Drivers/gateBox.groovy"
			   ) {
		capability "Momentary"
		capability "Contact Sensor"
		capability "Refresh"
		attribute "commsError", "bool"
	}
	preferences {
		if (!getDataValue("applicationVersion")) {
			input ("device_IP", "text", title: "Device IP (Current = ${getDataValue("deviceIP")})")
		}
		input ("reverseSense", "bool", title: "Reverse reported Open and Close Status.", defaultValue: false)
		input ("cycleTime", "number",title: "Nominal Door Cycle Time (seconds)",
			   defaultValue: 30)
		input ("refreshInterval", "enum", title: "Device Refresh Interval (minutes)", 
			   options: ["1", "5", "15", "30"], defaultValue: "30")
		input ("nameSync", "enum", title: "Synchronize Names", defaultValue: "none",
			   options: ["none": "Don't synchronize",
						 "device" : "bleBox device name master", 
						 "hub" : "Hubitat label master"])
		input ("debug", "bool", title: "Enable debug logging", defaultValue: false)
		input ("descriptionText", "bool", title: "Enable description text logging", defaultValue: true)
        input ("authLogin", "text", title: "Optional Auth login")
        input ("authPassword", "text", title: "Auth password")	}
}

def installed() {
	logInfo("Installing...")
	runIn(2, updated)
}

def updated() {
	logInfo("Updating...")
	unschedule()

	if (!getDataValue("applicationVersion")) {
		if (!device_IP) {
			logWarn("updated:  deviceIP  is not set.")
			return
		}
		updateDataValue("deviceIP", device_IP)
		logInfo("Device IP set to ${getDataValue("deviceIP")}")
		//	Update device name on manual installation to standard name
		sendGetCmd("/api/device/state", "setDeviceName")
		pauseExecution(1000)
	}

	switch(refreshInterval) {
		case "1" : runEvery1Minute(refresh); break
		case "5" : runEvery5Minutes(refresh); break
		case "15" : runEvery15Minutes(refresh); break
		default: runEvery30Minutes(refresh)
	}
	state.errorCount = 0
	updateDataValue("driverVersion", driverVer())

	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")
	logInfo("Refresh interval set for every ${refreshInterval} minute(s).")

	if (!getDataValue("mode")) {
		sendGetCmd("/api/gate/state", "setGateType")
	}
	if (nameSync == "device" || nameSync == "hub") { runIn(5, syncName) }
	runIn(2, refresh)
}

def setDeviceName(response) {
	def cmdResponse = parseInput(response)
	logDebug("setDeviceData: ${cmdResponse}")
	device.setName(cmdResponse.device.type)
	logInfo("setDeviceData: Device Name updated to ${cmdResponse.device.type}")
}

def setGateType(response) {
	def cmdResponse = parseInput(response)
	logDebug("setGateType: <b>${cmdResponse}")
	if (cmdResponse == "error") { return }
	def mode
	switch(cmdResponse.gateType) {
		case "0": mode = "slidingDoor"; break
		case "1": mode = "garageDoor"; break
		case "2": mode = "overDoor"; break
		case "3": mode = "door"; break
		default: mode = "notSet"
	}
	updateDataValue("mode", mode)
	logInfo("setGateType: Gate Type set to ${mode}")
}

//	===== Commands and Parse Returns =====
def push() {
	logDebug("push: currently ${device.currentValue("contact")}")
	sendGetCmd("/s/p", "commandParse")
	runIn(cycleTime.toInteger(), refresh)
}

def refresh() {
	logDebug("refresh")
	sendGetCmd("/api/gate/state", "commandParse")
}

def commandParse(response) {
	def cmdResponse = parseInput(response)
	logDebug("commandParse: ${cmdResponse}")
	if (cmdResponse.gate) { cmdResponse = cmdResponse.gate }
	def closedPos = 0
	if (reverseSense == true) { closedPos = 100 }
	def contact = "open"
	if (cmdResponse.currentPos == closedPos) { contact = "closed" }
	sendEvent(name: "contact", value: contact)
}

//	===== Name Sync Capability =====
def syncName() {
	logDebug("syncName. Synchronizing device name and label with master = ${nameSync}")
	if (nameSync == "hub") {
		sendPostCmd("/api/device/set",
					"""{"device":{"deviceName":"${device.label}"}}""",
					"nameSyncHub")
	} else if (nameSync == "device") {
		sendGetCmd("/api/device/state", "nameSyncDevice")
	}
}
def nameSyncHub(response) {
	def cmdResponse = parseInput(response)
	logDebug("nameSyncHub: ${cmdResponse}")
	logInfo("Setting device label to that of the bleBox device.")
}
def nameSyncDevice(response) {
	def cmdResponse = parseInput(response)
	logDebug("nameSyncDevice: ${cmdResponse}")
	def deviceName = cmdResponse.deviceName
	device.setLabel(deviceName)
	logInfo("Hubit name for device changed to ${deviceName}.")
}

//	===== Communications =====
def getAuthorizationHeader() {
		def encoded = "${authLogin}:${authPassword}".bytes.encodeBase64()
		return "Basic ${encoded}"	
}

private sendGetCmd(command, action){
	logDebug("sendGetCmd: ${command} / ${action} / ${getDataValue("deviceIP")}")
	state.lastCommand = [type: "get", command: "${command}", body: "n/a", action: "${action}"]
	runIn(3, setCommsError)
    def parameters = [ method: "GET",
					  path: command,
					  protocol: "hubitat.device.Protocol.LAN",
					  headers: [
						  Host: getDataValue("deviceIP"),
						  Authorization: getAuthorizationHeader()
					  ]]
	sendHubCommand(new hubitat.device.HubAction(parameters, null, [callback: action]))
}
private sendPostCmd(command, body, action){
	logDebug("sendGetCmd: ${command} / ${body} / ${action} / ${getDataValue("deviceIP")}")
	state.lastCommand = [type: "post", command: "${command}", body: "${body}", action: "${action}"]
	runIn(3, setCommsError)
	def parameters = [ method: "POST",
					  path: command,
					  protocol: "hubitat.device.Protocol.LAN",
					  body: body,
					  headers: [
						  Host: getDataValue("deviceIP"),
						  Authorization: getAuthorizationHeader()
					  ]]
	sendHubCommand(new hubitat.device.HubAction(parameters, null, [callback: action]))
}
def parseInput(response) {
	unschedule(setCommsError)
	state.errorCount = 0
	sendEvent(name: "commsError", value: false)
	try {
		def jsonSlurper = new groovy.json.JsonSlurper()
		return jsonSlurper.parseText(response.body)
	} catch (error) {
		logWarn "CommsError: ${error}."
	}
}
def setCommsError() {
	logDebug("setCommsError")
	if (state.errorCount < 3) {
		state.errorCount+= 1
		repeatCommand()
		logWarn("Attempt ${state.errorCount} to recover communications")
	} else if (state.errorCount == 3) {
		state.errorCount += 1
		if (getDataValue("applicationVersion")) {
			logWarn("setCommsError: Parent commanded to poll for devices to correct error.")
			parent.updateDeviceIps()
			runIn(90, repeatCommand)
		}
	} else {
		sendEvent(name: "commsError", value: true)
		logWarn "setCommsError: No response from device.  Refresh.  If off line " +
				"persists, check IP address of device."
	}
}
def repeatCommand() { 
	logDebug("repeatCommand: ${state.lastCommand}")
	if (state.lastCommand.type == "post") {
		sendPostCmd(state.lastCommand.command, state.lastCommand.body, state.lastCommand.action)
	} else {
		sendGetCmd(state.lastCommand.command, state.lastCommand.action)
	}
}


//	===== Utility Methods =====
def logInfo(msg) {
	if (descriptionText == true) { log.info "<b>${device.label} ${driverVer()}</b> ${msg}" }
}
def logDebug(msg){
	if(debug == true) { log.debug "<b>${device.label} ${driverVer()}</b> ${msg}" }
}
def logWarn(msg){ log.warn "<b>${device.label} ${driverVer()}</b> ${msg}" }

//	end-of-file
