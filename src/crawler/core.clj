(ns crawler.core
  (:require [clojure.core.async :as async :refer [>! <! put! go chan go-loop]]
            [taoensso.timbre :as timbre :refer [log]]

            [crawler.crawl-strategy :refer [enqueue stop! get-crawler]]
            [crawler.utils :refer [tap-it as-ch flatten-channel]]
            [crawler.url-filter :refer [crawl? crawled! one-pass-archiver]]
            [crawler.robots :refer [crawlable? crawl-delay clj-robots-filter]]
            [crawler.links :refer [extract-followable-links]]))

(timbre/set-config! [:appenders :standard-out :async?] true)

(defn- crawler* [{:keys [queue-length
                         handler-ch
                         async-par
                         comp-par
                         link-extraction-fn
                         url-filter
                         crawl-strategy] :as opts}]

  (let [fetch-q (chan (async/dropping-buffer queue-length))
        fetched-q (chan (async/dropping-buffer queue-length))]

    ;; Fetch pages
    (async/pipeline-async async-par fetched-q fetch fetch-q)

    (let [fetched-mult (async/mult fetched-q)]

      ;; Pass scraped pages off to hander
      (async/pipe (tap-it fetched-mult) handler-ch)

      ;; Pass scraped pages back to the crawl strategy.
      (async/pipe (tap-it fetched-mult) (as-ch (partial crawled! url-filter)))
      
      (let [new-link-ch (chan)]

        ;; Extract links
        (async/pipeline comp-par new-link-ch 
                        link-extraction-fn
                        (tap-it fetched-mult))
        
        ;; Pass new links back to the fetch queue 
        (async/pipe (flatten-channel new-link-ch)
                    (as-ch (partial enqueue crawl-strategy fetch-q)))))))

(def default-options
  {:queue-length 10000
   
   })

(defn crawler
  "Returns a channel which will queue URLs for crawling. This should
  be used for (re)seeding the search."
  [& [opts]]
  (let [options (merge (or opts {}) default-options)]
    (crawler* options)))

