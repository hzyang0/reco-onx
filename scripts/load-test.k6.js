import http from "k6/http";
import {check} from "k6";

export const options = {
    scenarios: {
        recommend: {
            executor: "constant-vus",
            vus: Number(__ENV.VUS || 20),
            duration: __ENV.DURATION || "30s"
        }
    },
    thresholds: {
        http_req_failed: ["rate<0.01"],
        http_req_duration: ["p(95)<500"]
    }
};

const baseUrl = __ENV.BASE_URL || "http://host.docker.internal:18081";

export default function () {
    const response = http.get(`${baseUrl}/recommend?userId=123&scene=buy_first&limit=10`);
    check(response, {
        "recommend returns 200": (value) => value.status === 200,
        "recommend returns ten items": (value) => (value.json("items") || []).length === 10
    });
}
