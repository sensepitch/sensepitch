import asyncio
import aiohttp
import random
import time
import os

BASE_URL = os.getenv("PROXY_URL", "https://localhost:17443")

FILES = [
    "/1mb.img",
#    "/100kb.img",
#    "/10kb.img",
    # add more URLs here
]

async def fetch(session, url):
    try:
        async with session.get(url, ssl=False) as response:
            body = await response.read()
            return len(body), None  # bytes, error
    except Exception as e:
        return 0, e  # bytes, error

async def worker(session, duration):
    reqs = 0
    bytes_rx = 0
    errors = 0
    deadline = time.time() + duration

    while time.time() < deadline:
        file = random.choice(FILES)
        url = f"{BASE_URL}{file}"
        b, err = await fetch(session, url)
        reqs += 1
        bytes_rx += b
#        print(err)
        if err is not None:
            errors += 1

    return reqs, bytes_rx, errors

async def run_load(concurrency, duration):
    if concurrency == 0:
        # print(f"\nPausing for {duration} seconds...")
        await asyncio.sleep(duration)
        return {"requests": 0, "bytes": 0, "errors": 0}
    async with aiohttp.ClientSession() as session:
        results = await asyncio.gather(
            *[asyncio.create_task(worker(session, duration)) for _ in range(concurrency)]
        )
    # aggregate
    total_reqs = sum(r for r, _, _ in results)
    total_bytes = sum(b for _, b, _ in results)
    total_errors = sum(e for _, _, e in results)
    return {"requests": total_reqs, "bytes": total_bytes, "errors": total_errors}

async def main():
    phases = [
        (100, 5),
        (1, 3),
        (100, 5),
        (1, 3),

# force collision with connection release
#        (1000, 1),
#        (0, 1),
#        (1000, 1),
#        (0, 1),

#        (1, 5),
#        (100, 3),
#        (1, 5),
#        (100, 3),
#        (1, 5),
#        (100, 3),
#        (1, 5),
#        (100, 3),
#        (1, 5),
    ]
    for concurrency, duration in phases:
        print(f"\nRunning {concurrency} workers for {duration} seconds...")
        stats = await run_load(concurrency, duration)
        print(f"Results: {stats['requests']} requests, {stats['bytes']} bytes received, {stats['errors']} errors")

if __name__ == "__main__":
    asyncio.run(main())
