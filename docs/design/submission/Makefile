# Build the Phase 1 design summary PDF for submission.
#   make        -> design-summary.pdf
#   make clean  -> remove build artifacts
PDF  := design-summary.pdf
SRC  := design-summary.tex preamble.tex

$(PDF): $(SRC)
	pdflatex -interaction=nonstopmode -halt-on-error design-summary.tex
	pdflatex -interaction=nonstopmode -halt-on-error design-summary.tex
	@rm -f design-summary.aux design-summary.log design-summary.out
	@echo "Built $(PDF) ($$(pdfinfo $(PDF) 2>/dev/null | awk '/^Pages/{print $$2}') pages)"

.PHONY: clean
clean:
	rm -f design-summary.aux design-summary.log design-summary.out $(PDF)
