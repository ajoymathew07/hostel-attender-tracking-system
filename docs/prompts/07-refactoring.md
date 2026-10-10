# 07 — Refactoring

Simplifying the codebase so every part can be explained in the mid-eval viva.

## 6 Oct 2026 — Walk through the code

> I see that the directory structure is java/in/ac/iitm/hostel/duty.  why do we need such long directory path where each directory only contains one directory.. is there any purpose for this .. if not simplify the directory structureI am going through the code of the project, so that I can explain it during my mid eval.. Tell me the order in which the PRs were created and what files and functions were written by along with the overall idea of what it does.. give it PR by PR, one short paragraph each

**Techniques:** context, output format.

## 6 Oct 2026 — Remove non-functional code

> Can you simplify the directory structure? and remove any unnecessary code that doesn't contribute any meaninful feature to our application.. I have to explain my code for my mid eval. So, I want to make sure that all parts of the code are functional.. If there is non-functional part of the code, remove it or if there is unnecessary complication that leads to many file or something that can be simplied without changing the observable behaviour let me know.. Act as a senior Java reviewer for this.. first list what you want to remove and why, then make the changes.. Make these changes, test that everything works and then open a PR.. all existing tests must pass without changing what they assert

**Techniques:** persona, prompt chaining (list before change), constraints (no behaviour change), acceptance criteria.

> Can you also shorten out the comments at the top of a function or class and keep it concise, only explaining what, and why(if applicable).. don't describe the history of the code like "this was added in PR 5".. commit and push to the same PR

**Techniques:** constraints, negative prompting.

## 7 Oct 2026 — Fewer files

> I want to make a PR to reduce the number of files of the code.. so its basically a refactoring work.. The things that I am about to say do it only if it is feasible none of the changes that will be made should affect the functionality or the external behaviour of our code.. Larger code is fine but right now there are too many files.. Some of the examples to reduce number of files Dispatch* in application and domain, Floor*.java, put roomfloor, floor proximity, tiebreak all into one file or something..  Can we combine all these exceptions into one file, all signed in states and similar changes.. Basically, I don't want so much modularity, reduce the number of files.. don't break the ArchUnit layer rules while merging files.. run the full test suite after each group of changes and give me a before/after file count

**Techniques:** few-shot (examples of what to merge), constraints (no behaviour change, keep layer rules), acceptance criteria, output format.
