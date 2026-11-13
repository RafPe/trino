// Command gate is the decision service of the lab (Contract L2).
//
// It answers the external routing call of Trino Gateway (POST /gateway/route) and the
// ext_authz HTTP call of Envoy (/envoy/check/<group>/...). For a query that the predictor
// knows, it adds the session properties required_workers_count and
// required_workers_max_wait_time. In every other case, and on every error, it adds nothing.
package main

import (
	"log"
	"net/http"
	"os"
	"strconv"
	"time"
)

func main() {
	cfg, err := configFromEnv(os.Getenv)
	if err != nil {
		log.Fatalf("invalid configuration: %v", err)
	}
	g := newGate(cfg)
	addr := ":" + strconv.Itoa(cfg.port)
	log.Printf("gate listening on %s, groups=%v default=%s predictor-timeout=%s delay=%s",
		addr, cfg.groupNames(), cfg.defaultGroup, cfg.predictorTimeout, cfg.delay)
	server := &http.Server{
		Addr:              addr,
		Handler:           g.handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	log.Fatal(server.ListenAndServe())
}
