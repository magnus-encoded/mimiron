(ns mimiron.jev
  "Asks TypeSafe's Jev how loudly a dictated line would be said, so a yell can be
  styled. Only the line's text is sent, never logged. Without TYPESAFE_API_KEY,
  or when the call is slow or fails, the answer is nil and the text stays as heard."
  (:require [clojure.string :as str]
            [jsonista.core :as json])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(def ^:private endpoint "https://api.typesafe.ai/v1/systemone")
(def ^:private timeout-ms 600)

(def ^:private question
  {:type "choice"
   :instructions "Dictation produced `heard`. It is about to be typed into the yell chat channel in World of Warcraft, where everyone nearby reads it. How loudly would the player be saying it?"
   :criteria {:plain "An ordinary remark or request, said calmly"
              :shout "A shout, battle cry, warning or rallying call that someone would yell, e.g. For the Horde, Run, Lok'tar ogar"
              :scream "A furious or panicked scream where the speaker is at full volume"}})

(defonce ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofMillis timeout-ms))
             (.build))))

(defn loudness
  "Returns :plain, :shout or :scream, or nil when unavailable."
  [text]
  (when-let [key (System/getenv "TYPESAFE_API_KEY")]
    (try
      (let [body (json/write-value-as-string
                  {:model "jev-latest" :state {:heard text} :questions {:loudness question}})
            req (-> (HttpRequest/newBuilder (URI/create endpoint))
                    (.timeout (Duration/ofMillis timeout-ms))
                    (.header "Authorization" (str "Bearer " key))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    (.build))
            res (.send ^HttpClient @client req (HttpResponse$BodyHandlers/ofString))]
        (when (= 200 (.statusCode res))
          (some-> (json/read-value (.body res) (json/object-mapper {:decode-key-fn keyword}))
                  :answers :loudness :choice
                  keyword #{:plain :shout :scream})))
      (catch Exception _ nil))))

(defn- capitalise-words [s]
  (str/replace s #"(^|\s)(\p{L})" (fn [[_ gap letter]] (str gap (str/upper-case letter)))))

(defn- end-with-bang [s]
  (str (str/replace s #"[.!?,;:\s]+$" "") "!"))

(defn style
  "The text styled for how loudly it was said: words capitalised and an
  exclamation mark for a shout, all capitals for a scream. Same words."
  [text how]
  (case how
    :shout (end-with-bang (capitalise-words text))
    :scream (end-with-bang (str/upper-case text))
    text))

(defn shape
  "Styles a yelled line using Jev; the line as heard when Jev is unavailable."
  [text]
  (style text (loudness text)))
