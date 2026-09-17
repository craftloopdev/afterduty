import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { LoaderBoundary } from "./LoaderBoundary";
import { UnauthorizedError, UpstreamError } from "@/lib/api/errors";
import type { LoaderState } from "@/lib/hooks/useLoader";

// The offline shell precedence (§D.3/§H.5): a connectivity-level load failure must
// render the BRANDED OfflineState (never the generic error / white screen) on a
// cold launch with no network. Online failures still show the generic error.

const online = vi.fn();
vi.mock("@/lib/native/network", () => ({ useOnline: () => online() }));

function stateOf<T>(over: Partial<LoaderState<T>>): LoaderState<T> {
  return { data: null, error: null, loading: false, refetch: vi.fn(), ...over };
}

beforeEach(() => {
  vi.clearAllMocks();
  online.mockReturnValue(true);
});

describe("LoaderBoundary — offline shell", () => {
  it("renders the branded OfflineState when a load fails AND the device is offline", () => {
    online.mockReturnValue(false);
    render(
      <LoaderBoundary state={stateOf({ error: new UpstreamError(500, "boom") })} skeleton={<div>load</div>}>
        {() => <div>data</div>}
      </LoaderBoundary>,
    );
    expect(screen.getByText(/you.re offline/i)).toBeInTheDocument();
    expect(screen.getByText(/needs a connection to load your claim/i)).toBeInTheDocument();
    expect(screen.queryByText(/something went wrong/i)).not.toBeInTheDocument();
  });

  it("renders the generic error when a load fails but the device is online", () => {
    online.mockReturnValue(true);
    render(
      <LoaderBoundary state={stateOf({ error: new UpstreamError(500, "boom") })} skeleton={<div>load</div>}>
        {() => <div>data</div>}
      </LoaderBoundary>,
    );
    expect(screen.getByText(/something went wrong/i)).toBeInTheDocument();
    expect(screen.queryByText(/you.re offline/i)).not.toBeInTheDocument();
  });

  it("auth errors stay the gate's concern even offline (no offline shell)", () => {
    online.mockReturnValue(false);
    render(
      <LoaderBoundary
        state={stateOf({ error: new UnauthorizedError() })}
        skeleton={<div>load</div>}
      >
        {() => <div>data</div>}
      </LoaderBoundary>,
    );
    expect(screen.getByText(/please sign in again/i)).toBeInTheDocument();
    expect(screen.queryByText(/you.re offline/i)).not.toBeInTheDocument();
  });

  it("renders data when present", () => {
    render(
      <LoaderBoundary state={stateOf<string>({ data: "ok" })} skeleton={<div>load</div>}>
        {(d) => <div>{d}</div>}
      </LoaderBoundary>,
    );
    expect(screen.getByText("ok")).toBeInTheDocument();
  });
});
