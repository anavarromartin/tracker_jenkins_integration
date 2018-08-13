// https://stackoverflow.com/questions/37864542/jenkins-pipeline-notserializableexception-groovy-json-internal-lazymap/38439681#38439681
import groovy.json.JsonSlurperClassic
import groovy.transform.Field

@Field String HOST = 'https://www.pivotaltracker.com'
@Field String JENKINS_PT_CREDENTIAL_ID = '????'

HttpResponse doGetHttpRequest(String requestUrl) {
    println requestUrl
    println "getting $PT_API_KEY"
    URL url = new URL(requestUrl)
    HttpURLConnection connection = url.openConnection()

    connection.setRequestMethod("GET")
    connection.setRequestProperty("X-TrackerToken", "$PT_API_KEY")

    //get the request
    connection.connect()

    //parse the response
    HttpResponse resp = new HttpResponse(connection)

    if (resp.isFailure()) {
        error("\nGET from URL: $requestUrl\n  HTTP Status: $resp.statusCode\n  Message: $resp.message\n  Response Body: $resp.body")
    }

    println "Request (GET):\n  URL: $requestUrl"
    println "Response:\n  HTTP Status: $resp.statusCode\n  Message: $resp.message\n  Response Body: $resp.body"

    return resp
}

/**
 * Posts the json content to the given url and ensures a 200 or 201 status on the response.
 * If a negative status is returned, an error will be raised and the pipeline will fail.
 */
HttpResponse doPostHttpRequestWithJson(String json, String requestUrl) {
    return doHttpRequestWithJson(json, requestUrl, "POST")
}

/**
 * Posts the json content to the given url and ensures a 200 or 201 status on the response.
 * If a negative status is returned, an error will be raised and the pipeline will fail.
 */
HttpResponse doPutHttpRequestWithJson(String json, String requestUrl) {
    return doHttpRequestWithJson(json, requestUrl, "PUT")
}

HttpResponse doDeleteHttpRequestWithJson(String json, String requestUrl) {
    return doHttpRequestWithJson(json, requestUrl, "DELETE")
}

/**
 * Post/Put the json content to the given url and ensures a 200 or 201 status on the response.
 * If a negative status is returned, an error will be raised and the pipeline will fail.
 * verb - PUT or POST
 */
HttpResponse doHttpRequestWithJson(String json, String requestUrl, String verb) {
    URL url = new URL(requestUrl)
    HttpURLConnection connection = url.openConnection()

    connection.setRequestMethod(verb)
    connection.setRequestProperty("Content-Type", "application/json")
    connection.setRequestProperty("X-TrackerToken", "$PT_API_KEY")
    connection.doOutput = true;

    //write the payload to the body of the request
    def writer = new OutputStreamWriter(connection.outputStream)
    writer.write(json)
    writer.flush()
    writer.close()

    //post the request
    connection.connect()

    //parse the response
    HttpResponse resp = new HttpResponse(connection)

    if (resp.isFailure()) {
        error("\n$verb to URL: $requestUrl\n    JSON: $json\n    HTTP Status: $resp.statusCode\n    Message: $resp.message\n    Response Body: $resp.body")
    }

    println "Request ($verb):\n  URL: $requestUrl\n  JSON: $json"
    println "Response:\n  HTTP Status: $resp.statusCode\n  Message: $resp.message\n  Response Body: $resp.body"

    return resp
}

class HttpResponse {

    String body
    String message
    Integer statusCode
    boolean failure = false

    public HttpResponse(HttpURLConnection connection) {
        this.statusCode = connection.responseCode
        this.message = connection.responseMessage

        if (statusCode == 200 || statusCode == 201) {
            this.body = connection.content.text//this would fail the pipeline if there was a 400
        } else {
            this.failure = true
            this.body = connection.getErrorStream().text
        }

        connection = null //set connection to null for good measure, since we are done with it
    }
}

pipeline {
    agent any
    stages {
        stage('Check for rejected stories') {
            steps {
                withCredentials([string(credentialsId: JENKINS_PT_CREDENTIAL_ID, variable: 'PT_API_KEY')]) {
                    script {
                        if(hasRejectedStories(env.PROJECT_ID)) {
                            error("There are rejected stories that haven't been accepted!!")
                        }
                    }
                }
            }
        }
    }
}

def jsonParse(def json) {
    new groovy.json.JsonSlurperClassic().parseText(json)
}

def hasRejectedStories(projectId) {
    stories = jsonParse(doGetHttpRequest("${HOST}/services/v5/projects/${projectId}/search?query=(-state%3Aunstarted+AND+-state%3Aaccepted)").body).stories.stories

    for (int i = 0; i < stories.size(); i++) {
        story = stories[i]
        println "Story ${stories[i].id} "

        transitions = jsonParse(doGetHttpRequest("${HOST}/services/v5/projects/${projectId}/stories/${story.id}/transitions").body)
        for (int j = transitions.size() - 1; j > 0; j--) {
            transition = transitions[j]
            if (transition.state == "rejected") {
                return true
            }
        }
    }
    return false
}

def findPendingCiLabelId(story) {
    labelsList = story.labels
    for (int i = 0; i < labelsList.size(); i++) {
        if (labelsList[i].name == "pending_ci") {
            return labelsList[i].id
        }
    }
    return ""
}

