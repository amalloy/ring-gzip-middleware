(ns ring.middleware.gzip
  (:require [clojure.java.io :as io]
            clojure.reflect)
  (:import (java.util.zip GZIPOutputStream)
           (java.io InputStream
                    OutputStream
                    ByteArrayOutputStream
                    ByteArrayInputStream
                    Closeable
                    File
                    PipedInputStream
                    PipedOutputStream)))

; only available on JDK7
(def ^:private flushable-gzip?
  (delay (->> (clojure.reflect/reflect GZIPOutputStream)
           :members
           (some (comp '#{[java.io.OutputStream boolean]} :parameter-types)))))

; only proxying here so we can specialize io/copy (which ring uses to transfer
; InputStream bodies to the servlet response) for reading from the result of
; piped-gzipped-input-stream
(defn- piped-gzipped-input-stream*
  []
  (proxy [PipedInputStream] []))

; exactly the same as do-copy for [InputStream OutputStream], but
; flushes the output on every chunk; this allows gzipped content to start
; flowing to clients ASAP (a reasonable change to ring IMO)
(defmethod @#'io/do-copy [(class (piped-gzipped-input-stream*)) OutputStream]
  [^InputStream input ^OutputStream output opts]
  (let [buffer (make-array Byte/TYPE (or (:buffer-size opts) 1024))]
    (loop []
      (let [size (.read input buffer)]
        (when (pos? size)
          (do (.write output buffer 0 size)
              (.flush output)
              (recur)))))))

(defn piped-gzipped-input-stream [in]
  (let [pipe-in (piped-gzipped-input-stream*)
        pipe-out (PipedOutputStream. pipe-in)]
    ; separate thread to prevent blocking deadlock
    (future
      (with-open [out (if @flushable-gzip?
                        (GZIPOutputStream. pipe-out true)
                        (GZIPOutputStream. pipe-out))]
        (if (seq? in)
          (doseq [string in]
            (io/copy (str string) out)
            (.flush out))
          (io/copy in out)))
      (when (instance? Closeable in)
        (.close ^Closeable in)))
    pipe-in))

(defn- gzip-into-byte-array? [resp]
  (if-let [length (get-in resp [:headers "Content-Length"])]
    (< (Long/parseLong length) 1048576)))

(defn gzipped-response [{:keys [body] :as resp}]
  (let [gzip-body (piped-gzipped-input-stream body)]
    (if (gzip-into-byte-array? resp)
      (let [output-stream (ByteArrayOutputStream.)
            _ (io/copy gzip-body output-stream)
            gzip-bytes (.toByteArray output-stream)]
        (-> resp
            (update-in [:headers]
                       #(-> %
                          (assoc "Content-Encoding" "gzip")
                          (assoc "Content-Length" (str (count gzip-bytes)))))
            (assoc :body (ByteArrayInputStream. gzip-bytes))))
      (-> resp
          (update-in [:headers]
                     #(-> %
                          (assoc "Content-Encoding" "gzip")
                          (dissoc "Content-Length")))
          (assoc :body gzip-body)))))

(defn- gzip-response [req {:keys [body status] :as resp}]
  (if (and (= status 200)
           (not (get-in resp [:headers "Content-Encoding"]))
           (or
            (and (string? body) (> (count body) 200))
            (and (seq? body) @flushable-gzip?)
            (instance? InputStream body)
            (instance? File body)))
    (let [accepts (get-in req [:headers "accept-encoding"] "")
          match (re-find #"(gzip|\*)(;q=((0|1)(.\d+)?))?" accepts)]
      (if (and match (not (contains? #{"0" "0.0" "0.00" "0.000"}
                                     (match 3))))
        (gzipped-response resp)
        resp))
    resp))

(defn wrap-gzip
  "Ring middleware that GZIPs response if client can handle it."
  [handler]
  (fn
    ([request]
     (gzip-response request (handler request)))
    ([request respond raise]
     (handler
      request
      (fn [response]
        (respond (gzip-response request response)))
      raise))))
